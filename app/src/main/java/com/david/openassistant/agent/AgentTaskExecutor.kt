package com.david.openassistant.agent

import com.david.openassistant.agent.AgentApiSummary
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterModel
import java.util.UUID
import kotlinx.coroutines.CancellationException

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

        // V37 Phase 1: Execution Start Validation
        val startValidation = store.validateTicket(ticket)
        diagnostics.info(
            event = "ownership_validation_completed",
            component = "lease",
            fields = mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "validation_stage" to "EXECUTION_START",
                "outcome" to if (startValidation is TicketValidationResult.Valid) "PASS" else "FAIL",
                "reason_code" to (startValidation as? TicketValidationResult.Mismatch)?.reason,
                "field" to (startValidation as? TicketValidationResult.Mismatch)?.field,
                "expected" to (startValidation as? TicketValidationResult.Mismatch)?.expected,
                "actual" to (startValidation as? TicketValidationResult.Mismatch)?.actual
            )
        )
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

        // Diagnostics for budget
        taskDiagnostics.info(
            "research_allocation_budget_applied",
            mapOf(
                "search_queries_target" to budget.searchQueriesTarget,
                "full_reads_target" to budget.fullReadsTarget,
                "max_rabbit_hole" to budget.maxRabbitHoleIterations
            )
        )

        diagnostics.info(
            event = "agent_milestone_started",
            component = "mission",
            fields = mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "worker_id" to ticket.workerId,
                "attempt_id" to leaseAttemptId,
                "generation" to generation,
                "task_order" to task.order,
                "execution_profile" to executionStrategy.profile.name,
                "allocation_profile" to allocationProfile.complexity.name,
                "tool_call_required" to (executionStrategy.profile == AgentExecutionProfile.FOCUSED_TOOL),
            ),
        )

        // V42 Phase 5: Pre-Dispatch Loop Guard
        val currentGoal = store.loadSnapshot().goals.firstOrNull { it.id == goal.id } ?: return WorkerOutcome.FAIL
        val activeCycle = currentGoal.researchCycles.firstOrNull { it.id == currentGoal.activeResearchCycleId }
            ?: return WorkerOutcome.FAIL // Normal execution requires a cycle after migration
            
        val currentFingerprint = calculateTaskFingerprint(currentGoal, task)
        val isAuthorizedRetry = task.retryAuthorizedFingerprint == currentFingerprint
        
        if (task.lastRequestFingerprint == currentFingerprint && task.attemptCount >= 1 && !isAuthorizedRetry) {
            val decision = ResearchRecoveryEngine.diagnoseAndSelectTactic(currentGoal, activeCycle, task, currentFingerprint)
            if (decision != null) {
                taskDiagnostics.warning("stall_detected_triggering_recovery", mapOf("diagnosis" to decision.diagnosis.name, "tactic" to decision.tactic.name))
                cycleManager.prepareRecovery(currentGoal, task, decision, currentFingerprint, ticket)
                return WorkerOutcome.RETRY 
            } else {
                taskDiagnostics.warning("identical_context_fingerprint_exhausted", mapOf("fingerprint" to currentFingerprint))
                diagnostics.info(
                    event = "research_cycles_exhausted",
                    component = "mission",
                    fields = mapOf("goal_id" to currentGoal.id, "task_id" to task.id)
                )
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
            taskDiagnostics.info("authorized_fingerprint_retry_consumed", mapOf("fingerprint" to currentFingerprint))
            store.updateGoal(goal.id) { current ->
                current.copy(tasks = current.tasks.map { t ->
                    if (t.id == task.id) t.copy(retryAuthorizedFingerprint = null) else t
                })
            }
        }
        
        val agentAttemptId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        
        // V34: Pre-calculate council role for the attempt record
        val councilRole = AgentCouncilPolicy.roleForCapability(task.capability)
        
        val attempt = AgentAttempt(
            id = agentAttemptId,
            taskId = task.id,
            status = AgentAttemptStatus.RUNNING,
            startedAt = startedAt,
            modelId = goal.executionModelId,
            councilRole = councilRole,
        )
        
        val runSnapshot = store.updateGoalAtomic(goal.id, ticket) { current ->
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
                                lastRequestFingerprint = currentFingerprint
                            )
                        } else {
                            existing
                        }
                    },
                    attempts = retainAttempts(current.attempts + attempt),
                    events = appendEvent(
                        current.events,
                        buildString {
                            append("Running milestone ${task.order + 1}: ${task.title}")
                            if (executionStrategy.profile != AgentExecutionProfile.FULL) {
                                append(" — ")
                                append(executionStrategy.explanation)
                            }
                        },
                    ),
                    error = null,
                )
            }
        }
        
        val startedGoal = runSnapshot.goals.firstOrNull { it.id == goal.id }
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

        // V34: Council Role-based model selection
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
                                cycleId = startedGoal.activeResearchCycleId
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
            taskDiagnostics.info("agent_milestone_cancelled")
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
        
        val isFree = current.executionModelId.isFreeRoute()
        if (!isFree) return ExecutionStallDiagnosis.NONE

        val intelligenceWallReached = task.attemptCount >= 3
        val progressStall = (task.attemptCount >= 2) && 
            (result.completionScore <= task.progressScore + MIN_PROGRESS_DELTA)
        
        val repetitiveSearchStall = (task.attemptCount >= 2) &&
            (task.capability in AgentCapability.RESEARCH_CAPABILITIES) &&
            (result.sources.asSequence().mapNotNull { s -> runCatching { java.net.URI(s.url).host }.getOrNull() }.distinct().toList().size < 2)
            
        val shallowResearchStall = (task.attemptCount >= 2) &&
            (task.capability in AgentCapability.RESEARCH_CAPABILITIES) &&
            ((result.summary.webFetchRequests ?: 0) < 1) &&
            ((result.summary.webSearchRequests ?: 0) >= 3)

        val verificationCircularity = (current.status == AgentGoalStatus.VERIFYING) &&
            (current.verificationCorrectionStreak >= 3) &&
            (current.plannerModelId.isFreeRoute())

        return when {
            intelligenceWallReached -> ExecutionStallDiagnosis.INTELLIGENCE_WALL
            progressStall -> ExecutionStallDiagnosis.PROGRESS_STALL
            repetitiveSearchStall -> ExecutionStallDiagnosis.REPETITIVE_SEARCH_STALL
            shallowResearchStall -> ExecutionStallDiagnosis.SHALLOW_RESEARCH_STALL
            verificationCircularity -> ExecutionStallDiagnosis.VERIFICATION_CIRCULARITY
            else -> ExecutionStallDiagnosis.NONE
        }
    }

    private fun evaluateStepQuality(
        task: AgentTask,
        result: AgentStepResult,
        goal: AgentGoal,
        profile: ResearchAllocationProfile?,
    ): StepQualityEvaluation {
        val criticalCheckFailed = result.completionScore < 0.2
        val passed = result.completionScore >= 0.7
        val reasons = mutableListOf<String>()
        if (criticalCheckFailed) reasons += "The response did not meet minimum completion standards."
        if (!passed) reasons += "The task was not fully completed."
        
        return StepQualityEvaluation(
            passed = passed,
            completionScore = result.completionScore,
            criticalCheckFailed = criticalCheckFailed,
            reasons = reasons
        )
    }

    private fun revalidatePreservedResearchMilestone(goal: AgentGoal, task: AgentTask, ticket: TaskExecutionTicket): Boolean {
        return false // Simplified for now
    }

    private suspend fun persistTaskResult(
        goal: AgentGoal,
        task: AgentTask,
        attempt: AgentAttempt,
        result: AgentStepResult,
        ticket: TaskExecutionTicket,
        taskDiagnostics: RuntimeDiagnostics,
    ): WorkerOutcome {
        val quality = evaluateStepQuality(task, result, goal, null)
        val diagnosis = detectExecutionStall(goal, task, result, quality.passed)
        
        val finishedAt = System.currentTimeMillis()
        val updatedAttempt = attempt.copy(
            status = if (quality.passed) AgentAttemptStatus.SUCCEEDED else AgentAttemptStatus.FAILED,
            finishedAt = finishedAt,
            completionTokens = result.summary.completionTokens,
            promptTokens = result.summary.promptTokens,
            totalTokens = result.summary.totalTokens,
            costUsd = result.summary.costUsd,
            webSearchRequests = result.summary.webSearchRequests,
            webFetchRequests = result.summary.webFetchRequests,
            discoveredLeads = result.summary.discoveredLeads,
            rabbitHoleIterations = result.summary.rabbitHoleIterations
        )

        val outputEvidence = AgentEvidence(
            taskId = task.id,
            kind = AgentEvidenceKind.MODEL_OUTPUT,
            title = "Task result: ${task.title}",
            summary = result.summary.toString(),
            content = result.content,
            sources = result.sources,
            cycleId = goal.activeResearchCycleId
        )

        beforeCommitHook?.beforeCommit(goal.id, task.id, ExecutionOwnership(ticket.workerId, ticket.attemptId, ticket.generation, task.id))

        val commitSnapshot = store.commitTaskResultAtomic(ticket) { current ->
            val updatedTasks = current.tasks.map { existing ->
                if (existing.id == task.id) {
                    existing.copy(
                        status = if (quality.passed) AgentTaskStatus.COMPLETED else AgentTaskStatus.FAILED,
                        progressScore = result.completionScore,
                        finishedAt = finishedAt,
                        lastError = if (quality.passed) null else quality.reasons.joinToString(" "),
                        failureClass = if (diagnosis != ExecutionStallDiagnosis.NONE) diagnosis.name else null,
                        outputEvidenceId = outputEvidence.id
                    )
                } else existing
            }
            current.copy(
                status = if (quality.passed) AgentGoalStatus.QUEUED else AgentGoalStatus.RUNNING,
                tasks = updatedTasks,
                attempts = retainAttempts(current.attempts.map { if (it.id == attempt.id) updatedAttempt else it }),
                evidence = current.evidence + outputEvidence,
                events = appendEvent(current.events, "Task ${task.order + 1} finished with status ${if (quality.passed) "COMPLETED" else "FAILED"}.")
            )
        }
        
        if (store.validateTicket(ticket) !is TicketValidationResult.Valid) {
            return WorkerOutcome.DONE
        }
        
        return if (quality.passed) WorkerOutcome.CONTINUE else WorkerOutcome.RETRY
    }

    private suspend fun persistTaskFailure(
        goalId: String,
        taskId: String,
        attemptId: String,
        error: Throwable,
        fingerprint: String,
        ticket: TaskExecutionTicket,
        models: List<OpenRouterModel>,
        taskDiagnostics: RuntimeDiagnostics,
    ): WorkerOutcome {
        val finishedAt = System.currentTimeMillis()
        
        beforeCommitHook?.beforeCommit(goalId, taskId, ExecutionOwnership(ticket.workerId, ticket.attemptId, ticket.generation, taskId))

        store.updateGoalAtomic(goalId, ticket) { current ->
            current.copy(
                status = AgentGoalStatus.QUEUED,
                tasks = current.tasks.map { existing ->
                    if (existing.id == taskId) {
                        existing.copy(
                            status = AgentTaskStatus.FAILED,
                            lastError = error.message,
                            finishedAt = finishedAt
                        )
                    } else existing
                },
                attempts = current.attempts.map { existing ->
                    if (existing.id == attemptId) {
                        existing.copy(
                            status = AgentAttemptStatus.FAILED,
                            finishedAt = finishedAt,
                            error = error.message
                        )
                    } else existing
                },
                events = appendEvent(current.events, "Task execution failed: ${error.message}")
            )
        }
        
        if (store.validateTicket(ticket) !is TicketValidationResult.Valid) {
            return WorkerOutcome.DONE
        }
        
        return WorkerOutcome.RETRY
    }

    private fun retainAttempts(attempts: List<AgentAttempt>): List<AgentAttempt> = attempts.takeLast(50)

    private fun appendEvent(events: List<AgentEvent>, message: String): List<AgentEvent> =
        events + AgentEvent(message = message)

    private fun appendEvidence(evidence: List<AgentEvidence>, item: AgentEvidence): List<AgentEvidence> =
        evidence + item

    companion object {
        private const val MIN_PROGRESS_DELTA = 0.05
    }
}

private fun String?.isFreeRoute(): Boolean = this?.contains("free") == true
