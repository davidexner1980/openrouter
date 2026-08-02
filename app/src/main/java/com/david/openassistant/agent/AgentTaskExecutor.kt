package com.david.openassistant.agent

import com.david.openassistant.agent.AgentRoutingPolicy
import com.david.openassistant.agent.IdempotencyEffectType
import com.david.openassistant.agent.IdempotencyRecord
import com.david.openassistant.agent.IdempotencyState
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.AgentModelSelector
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AgentTaskExecutor internal constructor(
    private val client: AgentOpenRouterClient,
    private val store: AgentStore,
    private val diagnostics: RuntimeDiagnostics,
    private val autonomyPolicy: AutonomyPolicy,
    private val beforeCommitHook: BeforeTaskResultCommitHook? = null,
) {
    private val cycleManager = ResearchCycleManager(store, client, diagnostics)

    suspend fun executeOneTask(
        apiKey: String,
        goal: AgentGoal,
        task: AgentTask,
        ticket: TaskExecutionTicket,
        models: List<OpenRouterModel> = emptyList(),
    ): WorkerOutcome {
        val taskDiagnostics = diagnostics.withContext(
            mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "worker_id" to ticket.workerId,
                "capability" to task.capability.name,
                "attempt" to (task.attemptCount + 1)
            )
        )

        val startValidation = store.validateTicket(ticket)
        if (startValidation !is TicketValidationResult.Valid) {
            return WorkerOutcome.DONE
        }

        if (revalidatePreservedResearchMilestone(goal, task, ticket)) {
            taskDiagnostics.info("agent_milestone_revalidated_skipped_execution")
            return WorkerOutcome.CONTINUE
        }
        
        val leaseAttemptId = ticket.attemptId
        val generation = ticket.generation
        val allocationProfile = AgentResearchAllocator.profileForGoal(goal, autonomyPolicy)
        val budget = AgentResearchAllocator.budgetForTask(goal, task, allocationProfile)
        val executionStrategy = selectAgentExecutionStrategy(goal, task)
        taskDiagnostics.section("Task ${task.order + 1}: ${task.title}")

        // V42 Phase 5: Pre-Dispatch Loop Guard
        val currentGoal = store.loadSnapshot().goals.firstOrNull { it.id == goal.id } ?: return WorkerOutcome.FAIL
        val activeCycle = currentGoal.researchCycles.firstOrNull { it.id == currentGoal.activeResearchCycleId }
            ?: return WorkerOutcome.FAIL
            
        val currentFingerprint = calculateTaskFingerprint(currentGoal, task)
        val isAuthorizedRetry = task.retryAuthorizedFingerprint == currentFingerprint
        
        if (task.lastRequestFingerprint == currentFingerprint && task.attemptCount >= 1 && !isAuthorizedRetry) {
            val decision = ResearchRecoveryEngine.diagnoseAndSelectTactic(currentGoal, activeCycle, task, currentFingerprint)
            if (decision != null) {
                taskDiagnostics.warning("stall_detected_triggering_recovery", mapOf("diagnosis" to decision.diagnosis.name, "tactic" to decision.tactic.name))
                cycleManager.prepareRecovery(currentGoal, task, decision, currentFingerprint, ticket)
                return WorkerOutcome.RETRY 
            } else {
                store.commitTaskResultAtomic(ticket) { current ->
                    current.copy(
                        status = AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
                        tasks = current.tasks.map { if (it.id == task.id) it.copy(status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE, failureClass = "RESEARCH_CYCLES_EXHAUSTED") else it },
                        events = appendEvent(current.events, "Research cycles exhausted: no novel strategy remaining.")
                    )
                }
                return WorkerOutcome.DONE
            }
        }
        
        if (isAuthorizedRetry) {
            store.updateGoal(goal.id) { current ->
                current.copy(tasks = current.tasks.map { t ->
                    if (t.id == task.id) t.copy(retryAuthorizedFingerprint = null) else t
                })
            }
        }
        
        val agentAttemptId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val councilRole = AgentCouncilPolicy.roleForCapability(task.capability)
        
        val attempt = AgentAttempt(
            id = agentAttemptId,
            taskId = task.id,
            status = AgentAttemptStatus.RUNNING,
            startedAt = startedAt,
            modelId = goal.executionModelId,
            councilRole = councilRole,
        )
        val startSnapshot = store.updateGoalAtomic(goal.id, ticket) { current ->
            if (current.status !in setOf(AgentGoalStatus.QUEUED, AgentGoalStatus.RUNNING)) {
                current
            } else {
                val updatedLease = if (current.executionLease?.workerId == ticket.workerId) {
                    current.executionLease.copy(heartbeatAt = System.currentTimeMillis())
                } else {
                    current.executionLease
                }
                current.copy(
                    status = AgentGoalStatus.RUNNING,
                    executionLease = updatedLease,
                    tasks = current.tasks.map { existing ->
                        if (existing.id == task.id) {
                            existing.copy(
                                status = AgentTaskStatus.RUNNING,
                                attemptCount = existing.attemptCount + 1,
                                lifetimeAttemptCount = existing.lifetimeAttemptCount + 1,
                                lastError = null,
                                startedAt = startedAt,
                                finishedAt = null,
                                cycleId = activeCycle.id
                            )
                        } else {
                            existing
                        }
                    },
                    attempts = retainAttempts(current.attempts + attempt),
                    events = appendEvent(current.events, "Running milestone ${task.order + 1}: ${task.title}"),
                    error = null,
                )
            }
        }
        val startedGoal = startSnapshot.goals.firstOrNull { it.id == goal.id }
        if (startedGoal?.status != AgentGoalStatus.RUNNING) return WorkerOutcome.DONE

        val lease = startedGoal.executionLease ?: return WorkerOutcome.FAIL
        val parentOperationId = "op-task-${UUID.randomUUID()}"
        val missionContext = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = lease.workerId,
            taskId = task.id,
            attemptId = leaseAttemptId,
            executionGeneration = lease.generation,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = parentOperationId,
        )

        val profile = AgentRoutingPolicy.profileForGoal(startedGoal)
        val councilModelId = AgentCouncilPolicy.selectModel(councilRole, profile, startedGoal.executionModelId)

        val timer = taskDiagnostics.startTimer("agent_milestone_execution_duration")
        return try {
            val result = client.executeTask(
                apiKey = apiKey,
                modelId = councilModelId,
                goal = startedGoal,
                task = task,
                requestContext = missionContext,
                onProgress = { source ->
                    val sanitizedSource = source.sanitizedForPersistence()
                    store.updateGoalAtomic(goal.id, ticket) { current ->
                        if (current.evidence.any { it.kind == AgentEvidenceKind.RESEARCH_HIT && it.content == sanitizedSource.url }) {
                            current
                        } else {
                            val hitEvidence = AgentEvidence(
                                taskId = task.id,
                                kind = AgentEvidenceKind.RESEARCH_HIT,
                                title = "Live Research Hit: ${sanitizedSource.title}",
                                summary = sanitizedSource.excerpt ?: "Discovered a relevant source URL during live research.",
                                content = sanitizedSource.url,
                                sources = listOf(sanitizedSource),
                                cycleId = activeCycle.id
                            )
                            current.copy(
                                evidence = current.evidence + hitEvidence,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }
                },
                models = models
            )
            timer.stop(mapOf("status" to "success"))
            persistTaskResult(startedGoal, task, attempt, result, ticket, taskDiagnostics)
        } catch (error: CancellationException) {
            timer.stop(mapOf("status" to "cancelled"))
            throw error
        } catch (error: Throwable) {
            timer.stop(mapOf("status" to "failed", "error_type" to error::class.java.simpleName))
            persistTaskFailure(goal.id, task.id, agentAttemptId, error, currentFingerprint, ticket, models, taskDiagnostics)
        }
    }

    private fun calculateTaskFingerprint(goal: AgentGoal, task: AgentTask): String {
        val activeCycle = goal.researchCycles.firstOrNull { it.id == goal.activeResearchCycleId }
        return FingerprintUtils.computeExecutionContextFingerprint(goal, task, activeCycle)
    }

    private fun detectExecutionStall(
        current: AgentGoal,
        task: AgentTask,
        result: AgentStepResult,
        qualityAccepted: Boolean,
    ): ExecutionStallDiagnosis {
        if (qualityAccepted) return ExecutionStallDiagnosis.NONE
        val intelligenceWallReached = task.attemptCount >= 3
        val progressStall = (task.attemptCount >= 2) && (result.completionScore <= task.progressScore + MIN_PROGRESS_DELTA)
        return when {
            intelligenceWallReached -> ExecutionStallDiagnosis.INTELLIGENCE_WALL
            progressStall -> ExecutionStallDiagnosis.PROGRESS_STALL
            else -> ExecutionStallDiagnosis.NONE
        }
    }

    private fun evaluateStepQuality(
        task: AgentTask,
        result: AgentStepResult,
        goal: AgentGoal,
        allocation: ResearchAllocationProfile? = null,
    ): StepQualityEvaluation {
        val criticalCheckFailed = result.acceptanceChecks.any { (it.status == AgentAcceptanceCheckStatus.FAIL) && (it.score < 0.25) }
        val researchQuality = ResearchQualityGate.evaluateStep(task, result, goal, autonomyPolicy, allocation)
        val passed = (result.completionScore >= MIN_STEP_COMPLETION_SCORE && !criticalCheckFailed && researchQuality.passed)
        val reasons = buildList {
            if (result.completionScore < MIN_STEP_COMPLETION_SCORE) add("Low completion score.")
            if (criticalCheckFailed) add("Critical check failed.")
            addAll(researchQuality.reasons)
        }
        return StepQualityEvaluation(passed = passed, completionScore = result.completionScore, criticalCheckFailed = criticalCheckFailed, reasons = reasons)
    }

    private fun revalidatePreservedResearchMilestone(goal: AgentGoal, task: AgentTask, ticket: TaskExecutionTicket): Boolean {
        if (task.status != AgentTaskStatus.FAILED) return false
        val evidence = task.outputEvidenceId?.let { id -> goal.evidence.firstOrNull { it.id == id } } ?: goal.evidence.lastOrNull { it.taskId == task.id } ?: return false
        val preservedAssessment = recoverPreservedResearchAssessment(task, evidence, goal.claims.filter { it.taskId == task.id }, autonomyPolicy, MIN_STEP_COMPLETION_SCORE) ?: return false
        val completedAt = System.currentTimeMillis()
        store.commitTaskResultAtomic(ticket) { current ->
            current.copy(tasks = current.tasks.map { if (it.id == task.id) it.copy(status = AgentTaskStatus.COMPLETED, progressScore = preservedAssessment.completionScore, finishedAt = completedAt) else it }, status = AgentGoalStatus.QUEUED)
        }
        return true
    }

    private fun persistTaskResult(
        startedGoal: AgentGoal,
        task: AgentTask,
        attempt: AgentAttempt,
        rawResult: AgentStepResult,
        ticket: TaskExecutionTicket,
        taskDiagnostics: RuntimeDiagnostics,
    ): WorkerOutcome {
        beforeCommitHook?.beforeCommit(startedGoal.id, task.id, ExecutionOwnership(ticket.workerId, ticket.attemptId, ticket.generation, ticket.taskId))
        val currentFingerprint = calculateTaskFingerprint(startedGoal, task)
        val result = rawResult.copy(sources = rawResult.sources.sanitizedForPersistence())
        val finishedAt = System.currentTimeMillis()
        val proposedEvidenceItem = AgentEvidence(
            taskId = task.id, kind = AgentEvidenceKind.MODEL_OUTPUT, title = task.title, summary = result.content.take(100),
            content = result.content, sources = result.sources, cycleId = startedGoal.activeResearchCycleId
        )
        val currentAfterCall = store.loadSnapshot().goals.firstOrNull { it.id == startedGoal.id } ?: return WorkerOutcome.FAIL
        val quality = evaluateStepQuality(task, result, currentAfterCall)
        val synthesisGap = synthesisGapDecision(task, result, quality.reasons)
        
        val persistedSnapshot = store.commitTaskResultAtomic(ticket) { current ->
            val updatedTasks = current.tasks.map { existing ->
                if (existing.id == task.id) {
                    existing.copy(
                        status = if (quality.passed) AgentTaskStatus.COMPLETED else AgentTaskStatus.FAILED,
                        progressScore = if (quality.passed) result.completionScore else maxOf(existing.progressScore, result.completionScore),
                        finishedAt = finishedAt,
                        lastRequestFingerprint = currentFingerprint
                    )
                } else existing
            }
            current.copy(
                status = if (quality.passed) AgentGoalStatus.QUEUED else AgentGoalStatus.RUNNING,
                tasks = updatedTasks,
                evidence = current.evidence + proposedEvidenceItem,
                attempts = retainAttempts(current.attempts.map { if (it.id == attempt.id) it.copy(status = if (quality.passed) AgentAttemptStatus.SUCCEEDED else AgentAttemptStatus.FAILED, finishedAt = finishedAt) else it })
            )
        }
        
        val persistedGoal = persistedSnapshot.goals.first { it.id == startedGoal.id }
        store.applyUsageOnceAtomic(ticket, "task_accounting_${attempt.id}", result.summary.totalTokens, result.summary.costUsd)
        return if (quality.passed) WorkerOutcome.CONTINUE else WorkerOutcome.RETRY
    }

    private fun persistTaskFailure(
        goalId: String, taskId: String, agentAttemptId: String, error: Throwable,
        currentFingerprint: String? = null, ticket: TaskExecutionTicket,
        models: List<OpenRouterModel> = emptyList(), taskDiagnostics: RuntimeDiagnostics
    ): WorkerOutcome {
        beforeCommitHook?.beforeCommit(goalId, taskId, ExecutionOwnership(ticket.workerId, ticket.attemptId, ticket.generation, taskId))
        val finishedAt = System.currentTimeMillis()
        store.commitTaskResultAtomic(ticket) { current ->
            current.copy(
                tasks = current.tasks.map { if (it.id == taskId) it.copy(status = AgentTaskStatus.FAILED, lastError = error.message, finishedAt = finishedAt) else it },
                status = AgentGoalStatus.QUEUED,
                attempts = retainAttempts(current.attempts.map { if (it.id == agentAttemptId) it.copy(status = AgentAttemptStatus.FAILED, finishedAt = finishedAt, error = error.message) else it })
            )
        }
        return WorkerOutcome.RETRY
    }

    private fun retainAttempts(attempts: List<AgentAttempt>): List<AgentAttempt> = attempts.takeLast(50)
    private fun appendEvent(events: List<AgentEvent>, message: String): List<AgentEvent> = events + AgentEvent(message = message)

    companion object {
        private const val MIN_PROGRESS_DELTA = 0.05
    }
}

private fun String?.isFreeRoute(): Boolean = this?.contains("free") == true
