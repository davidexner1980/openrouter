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

    suspend fun executeOneTask(
        apiKey: String,
        goal: AgentGoal,
        task: AgentTask,
        workerId: String,
        models: List<OpenRouterModel> = emptyList(),
    ): WorkerOutcome {
        if (revalidatePreservedResearchMilestone(goal, task)) {
            return WorkerOutcome.CONTINUE
        }
        val activeLease = goal.executionLease
        if (activeLease == null || activeLease.workerId != workerId || activeLease.taskId != task.id) {
            diagnostics.warning(
                "agent_task_executor_ownership_validation_failed",
                mapOf("goal_id" to goal.id, "task_id" to task.id, "worker_id" to workerId),
            )
            return WorkerOutcome.FAIL
        }
        val leaseAttemptId = activeLease.attemptId
        val generation = activeLease.generation
        val allocationProfile = AgentResearchAllocator.profileForGoal(goal, autonomyPolicy)
        val budget = AgentResearchAllocator.budgetForTask(goal, task, allocationProfile)
        
        val executionStrategy = selectAgentExecutionStrategy(goal, task)
        val startedAt = System.currentTimeMillis()

        // Diagnostics for budget
        diagnostics.info(
            "research_allocation_budget_applied",
            mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "search_queries_target" to budget.searchQueriesTarget,
                "full_reads_target" to budget.fullReadsTarget,
                "max_rabbit_hole" to budget.maxRabbitHoleIterations
            )
        )

        diagnostics.info(
            "agent_milestone_started",
            mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "worker_id" to workerId,
                "attempt_id" to leaseAttemptId,
                "generation" to generation,
                "task_order" to task.order,
                "capability" to task.capability.name,
                "attempt" to (task.attemptCount + 1),
                "execution_profile" to executionStrategy.profile.name,
                "allocation_profile" to allocationProfile.complexity.name,
                "tool_call_required" to (executionStrategy.profile == AgentExecutionProfile.FOCUSED_TOOL),
            ),
        )
        val agentAttemptId = UUID.randomUUID().toString()
        val attempt = AgentAttempt(
            id = agentAttemptId,
            taskId = task.id,
            status = AgentAttemptStatus.RUNNING,
            startedAt = startedAt,
            modelId = goal.executionModelId,
        )
        val startSnapshot = store.updateGoal(goal.id) { current ->
            if (current.status !in setOf(AgentGoalStatus.QUEUED, AgentGoalStatus.RUNNING)) {
                current
            } else {
                val updatedLease = if (current.executionLease?.workerId == workerId) {
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
        val startedGoal = startSnapshot.goals.firstOrNull { it.id == goal.id }
        if (startedGoal?.status != AgentGoalStatus.RUNNING) return WorkerOutcome.DONE

        val lease = startedGoal.executionLease ?: return WorkerOutcome.FAIL
        val currentFingerprint = calculateTaskFingerprint(startedGoal, task)
        
        if (task.lastRequestFingerprint == currentFingerprint && task.attemptCount >= 1) {
            val error = IllegalStateException("Identical context fingerprint detected; skipping repetitive provider request.")
            val ownership = ExecutionOwnership(
                workerId = lease.workerId,
                leaseAttemptId = lease.attemptId,
                executionGeneration = lease.generation,
                taskId = task.id,
            )
            return persistTaskFailure(goal.id, task.id, agentAttemptId, error, currentFingerprint, ownership, models)
        }
        
        val parentOperationId = "op-task-${UUID.randomUUID()}"
        val missionContext = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = lease.workerId,
            taskId = task.id,
            attemptId = leaseAttemptId,
            executionGeneration = lease.generation,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = parentOperationId,
        )

        return try {
            val result = client.executeTask(
                apiKey = apiKey,
                modelId = AgentRoutingPolicy.guardModel(startedGoal!!, startedGoal.executionModelId),
                goal = startedGoal,
                task = task,
                requestContext = missionContext,
                onProgress = { source ->
                    val sanitizedSource = source.sanitizedForPersistence()
                    store.updateGoal(goal.id) { current ->
                        if (current.evidence.any { it.kind == AgentEvidenceKind.RESEARCH_HIT && it.content == sanitizedSource.url }) {
                            current
                        } else {
                            val hitEvidence = AgentEvidence(
                                taskId = task.id,
                                kind = AgentEvidenceKind.RESEARCH_HIT,
                                title = "Live Research Hit: ${sanitizedSource.title}",
                                summary = sanitizedSource.excerpt ?: "Discovered a relevant source URL during live research.",
                                content = sanitizedSource.url,
                                sources = listOf(sanitizedSource)
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
            currentCoroutineContext().ensureActive()
            val ownership = ExecutionOwnership(
                workerId = lease.workerId,
                leaseAttemptId = lease.attemptId,
                executionGeneration = lease.generation,
                taskId = task.id,
            )
            persistTaskResult(startedGoal, task, attempt, result, ownership)
        } catch (error: CancellationException) {
            diagnostics.info("agent_milestone_cancelled", mapOf("goal_id" to goal.id, "task_id" to task.id))
            throw error
        } catch (error: Throwable) {
            val ownership = ExecutionOwnership(
                workerId = lease.workerId,
                leaseAttemptId = lease.attemptId,
                executionGeneration = lease.generation,
                taskId = task.id,
            )
            persistTaskFailure(goal.id, task.id, agentAttemptId, error, currentFingerprint, ownership, models)
        }
    }

    private fun calculateTaskFingerprint(goal: AgentGoal, task: AgentTask): String {
        val selectedContext = EvidenceContextSelector.select(goal, task)
        return buildString {
            append("ctx:").append(selectedContext.fingerprint).append(';')
            // Retry counters, timestamps, and model wording are deliberately excluded.
            // Only durable accepted state may change the progress fingerprint.
            goal.evidence
                .asSequence()
                .filter { it.taskId == task.id }
                .sortedBy { it.id }
                .forEach { evidence ->
                    append("e:").append(evidence.id).append(':').append(evidence.content.hashCode()).append(';')
                }
            goal.acceptedClaims
                .asSequence()
                .filter { it.taskId == task.id }
                .sortedBy { it.id }
                .forEach { claim ->
                    append("c:").append(claim.id).append(':').append(claim.contentHash).append(';')
                }
            task.acceptanceChecks
                .sortedBy { it.criterionId }
                .forEach { check ->
                    append("a:").append(check.criterionId).append(':').append(check.status.name).append(';')
                }
        }.hashCode().toString(16)
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
        evidence: List<AgentEvidence>,
        allocation: ResearchAllocationProfile? = null,
    ): StepQualityEvaluation {
        val criticalCheckFailed = result.acceptanceChecks.any {
            (it.status == AgentAcceptanceCheckStatus.FAIL) && (it.score < 0.25)
        }
        val researchQuality = ResearchQualityGate.evaluateStep(task, result, autonomyPolicy, allocation)
        val boundedResearchRecoveryAccepted = acceptsBoundedResearchRecovery(
            task = task,
            result = result,
            policy = autonomyPolicy,
        )
        val correctionClaimGatePassed = (task.capability != AgentCapability.CORRECT) ||
            hasPublishableCorrectionClaims(result.claims)
            
        val toolUseGatePassed = when (task.capability) {
            AgentCapability.TOOL_USE -> if (isSynthesisGapAnalysisTask(task)) {
                result.toolExecutions.any { execution ->
                    execution.succeeded && execution.toolName == "sandbox_workbench"
                }
            } else {
                result.toolExecutions.any { it.succeeded }
            }
            AgentCapability.TOOL_CREATE -> {
                val created = result.toolExecutions.any { it.succeeded && it.toolName == "create_tool_recipe" }
                val exercised = result.toolExecutions.any { it.succeeded && it.toolName.startsWith("recipe_") }
                created && exercised
            }
            else -> true
        }

        val impreciseSourceSelections = if (task.capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)) {
            findImpreciseClaimSourceSelections(
                claims = result.claims,
                evidence = evidence,
            )
        } else {
            emptyList()
        }
        val preciseSourceGatePassed = impreciseSourceSelections.isEmpty()

        val passed = boundedResearchRecoveryAccepted || (
            result.completionScore >= MIN_STEP_COMPLETION_SCORE &&
                !criticalCheckFailed &&
                researchQuality.passed &&
                correctionClaimGatePassed &&
                preciseSourceGatePassed &&
                toolUseGatePassed
            )

        val reasons = buildList {
            if (result.completionScore < MIN_STEP_COMPLETION_SCORE) {
                add("Milestone quality score ${result.completionScore.asPercent()} did not meet the ${MIN_STEP_COMPLETION_SCORE.asPercent()} completion gate.")
            }
            if (criticalCheckFailed) add("A critical milestone acceptance check failed.")
            addAll(researchQuality.reasons)
            if (!correctionClaimGatePassed) {
                add("The evidence-only correction produced no publishable structured claims.")
            }
            impreciseSourceSelections.take(5).forEach { selection ->
                add(
                    "Factual claim '${selection.claimId}' cites ${selection.citedUrl}, but " +
                        "${selection.betterMatchingUrl} is a materially closer same-site entity match " +
                        "inside the same referenced evidence bundle.",
                )
            }
            if (!toolUseGatePassed && (task.capability == AgentCapability.TOOL_USE)) {
                if (isSynthesisGapAnalysisTask(task)) {
                    add("The synthesis-gap analysis did not complete a successful sandbox_workbench execution against the preserved evidence.")
                } else {
                    add("The tool-use milestone did not complete a successful local tool call.")
                }
            }
            if (!toolUseGatePassed && (task.capability == AgentCapability.TOOL_CREATE)) {
                add("The Tool Foundry milestone did not both activate and exercise a tested recipe tool.")
            }
        }

        return StepQualityEvaluation(
            passed = passed,
            boundedResearchRecoveryAccepted = boundedResearchRecoveryAccepted,
            completionScore = result.completionScore,
            criticalCheckFailed = criticalCheckFailed,
            researchQuality = researchQuality,
            correctionClaimGatePassed = correctionClaimGatePassed,
            preciseSourceGatePassed = preciseSourceGatePassed,
            toolUseGatePassed = toolUseGatePassed,
            impreciseSourceSelections = impreciseSourceSelections,
            reasons = reasons,
        )
    }

    private fun revalidatePreservedResearchMilestone(goal: AgentGoal, task: AgentTask): Boolean {
        if (task.status != AgentTaskStatus.FAILED) return false
        val evidence = task.outputEvidenceId
            ?.let { evidenceId -> goal.evidence.firstOrNull { it.id == evidenceId } }
            ?: goal.evidence.lastOrNull { it.taskId == task.id }
            ?: return false
        val preservedAssessment = recoverPreservedResearchAssessment(
            task = task,
            evidence = evidence,
            claims = goal.claims.filter { it.taskId == task.id },
            policy = autonomyPolicy,
            minimumCompletionScore = MIN_STEP_COMPLETION_SCORE,
        ) ?: return false

        val completedAt = System.currentTimeMillis()
        val snapshot = store.updateGoal(goal.id) { current ->
            val currentTask = current.tasks.firstOrNull { it.id == task.id } ?: return@updateGoal current
            if (
                (currentTask.status != AgentTaskStatus.FAILED) ||
                (current.status == AgentGoalStatus.PAUSED || current.status.isFinalTerminalStatus())
            ) {
                return@updateGoal current
            }
            val updatedTasks = current.tasks.map { existing ->
                if (existing.id == task.id) {
                    existing.copy(
                        status = AgentTaskStatus.COMPLETED,
                        lastError = null,
                        acceptanceChecks = preservedAssessment.acceptanceChecks,
                        progressScore = maxOf(existing.progressScore, preservedAssessment.completionScore),
                        finishedAt = completedAt,
                    )
                } else {
                    existing
                }
            }
            val repairedClaims = current.claims.map { existing ->
                if (existing.taskId == task.id) {
                    repairOverAttributedClaim(existing, current.evidence)
                } else {
                    existing
                }
            }
            val repairedClaimsById = repairedClaims.associateBy { it.id }
            val repairedLinks = current.evidenceLinks.map { link ->
                when (repairedClaimsById[link.claimId]?.support) {
                    AgentClaimSupport.PARTIAL -> link.copy(relation = AgentEvidenceRelation.QUALIFIES)
                    AgentClaimSupport.CONTRADICTED -> link.copy(relation = AgentEvidenceRelation.CONTRADICTS)
                    else -> link
                }
            }
            val updatedGoal = current.copy(
                status = AgentGoalStatus.QUEUED,
                tasks = updatedTasks,
                claims = repairedClaims,
                evidenceLinks = repairedLinks,
                events = appendEvent(
                    current.events,
                    "Revalidated preserved milestone ${task.order + 1} under its corrected ${researchPassRole(task).name.lowercase()} gate; no provider call was needed.",
                ),
                error = null,
            )
            updatedGoal.copy(
                checkpoints = appendCheckpoint(
                    updatedGoal.checkpoints,
                    AgentCheckpoint(
                        sequence = (updatedGoal.checkpoints.maxOfOrNull { it.sequence } ?: 0) + 1,
                        completedTaskIds = updatedTasks.asSequence()
                            .filter { it.status == AgentTaskStatus.COMPLETED }
                            .map { it.id }
                            .toList(),
                        progressScore = updatedGoal.denseProgressScore,
                        note = "Milestone '${task.title}' passed corrected deterministic revalidation from preserved evidence.",
                    ),
                ),
            )
        }
        val revalidated = snapshot.goals
            .firstOrNull { it.id == goal.id }
            ?.tasks
            ?.firstOrNull { it.id == task.id }
            ?.status == AgentTaskStatus.COMPLETED
        if (revalidated) {
            diagnostics.info(
                "agent_milestone_revalidated",
                mapOf(
                    "goal_id" to goal.id,
                    "task_id" to task.id,
                    "research_role" to researchPassRole(task).name,
                    "source_count" to (evidence.sources.size),
                ),
            )
        }
        return revalidated
    }

    private fun persistTaskResult(
        startedGoal: AgentGoal,
        task: AgentTask,
        attempt: AgentAttempt,
        rawResult: AgentStepResult,
        ownership: ExecutionOwnership,
    ): WorkerOutcome {
        beforeCommitHook?.beforeCommit(startedGoal.id, task.id, ownership)
        val currentFingerprint = calculateTaskFingerprint(startedGoal, task)
        val result = recoverResearchAssessment(
            task = task,
            result = rawResult.copy(claims = normalizeDurableClaims(task, rawResult.claims)),
            policy = autonomyPolicy,
            metadataWasRepaired = rawResult.structuredOutputRepaired,
        ).let { recovered ->
            recovered.copy(sources = recovered.sources.sanitizedForPersistence())
        }
        val finishedAt = System.currentTimeMillis()
        val evidenceKind = when (task.capability) {
            AgentCapability.WEB_RESEARCH -> AgentEvidenceKind.WEB_RESEARCH
            AgentCapability.DEEP_RESEARCH -> AgentEvidenceKind.DEEP_RESEARCH
            AgentCapability.TOOL_USE,
            AgentCapability.TOOL_CREATE,
            -> AgentEvidenceKind.TOOL_RESULT
            else -> AgentEvidenceKind.MODEL_OUTPUT
        }
        val proposedEvidenceItem = AgentEvidence(
            taskId = task.id,
            kind = evidenceKind,
            title = task.title,
            summary = result.content.compactSummary(),
            content = durableEvidenceContent(result, MAX_EVIDENCE_CONTENT_CHARS),
            sources = result.sources,
        )
        val currentAfterCall = store.loadSnapshot().goals.firstOrNull { it.id == startedGoal.id } ?: return WorkerOutcome.FAIL
        val currentTaskAfterCall = currentAfterCall.tasks.firstOrNull { it.id == task.id }
            ?: return WorkerOutcome.FAIL
        val currentAttemptAfterCall = currentAfterCall.attempts.firstOrNull { it.id == attempt.id }
        if (!canCommitMilestoneResult(currentAfterCall, task.id, attempt.id, ownership)) {
            diagnostics.warning(
                "agent_late_milestone_result_discarded",
                mapOf(
                    "goal_id" to startedGoal.id,
                    "task_id" to task.id,
                    "worker_id" to ownership.workerId,
                    "attempt_id" to attempt.id,
                    "task_status" to currentTaskAfterCall.status.name,
                    "attempt_status" to currentAttemptAfterCall?.status?.name,
                ),
            )
            return WorkerOutcome.DONE
        }

        val allocationProfile = AgentResearchAllocator.profileForGoal(currentAfterCall, autonomyPolicy)
        val quality = evaluateStepQuality(task, result, currentAfterCall.evidence + proposedEvidenceItem, allocationProfile)
        val synthesisGapDecision = if (
            !quality.criticalCheckFailed &&
            quality.correctionClaimGatePassed &&
            quality.toolUseGatePassed
        ) {
            synthesisGapDecision(task, result, quality.researchQuality.reasons)
        } else {
            SynthesisGapDecision(emptyList(), requiresDeterministicAnalysis = false, qualifiesForBoundedPublication = false)
        }

        val persistedSnapshot = store.updateGoal(startedGoal.id) { current ->
            commitTaskResult(current, task, attempt.id, result, quality, synthesisGapDecision, finishedAt, proposedEvidenceItem, evidenceKind, currentFingerprint, ownership)
        }

        val persistedGoal = persistedSnapshot.goals.firstOrNull { it.id == startedGoal.id }
            ?: return WorkerOutcome.DONE
        if (persistedGoal.status == AgentGoalStatus.PAUSED || persistedGoal.status.isFinalTerminalStatus()) {
            return WorkerOutcome.DONE
        }
        
        // Final outcomes and diagnostics
        val effectiveQualityAccepted = quality.passed || (persistedGoal.tasks.firstOrNull { it.id == task.id }?.status == AgentTaskStatus.COMPLETED)
        val synthesisRecoveryQueued = !quality.passed && synthesisGapDecision.hasActionableGap && (persistedGoal.tasks.size > startedGoal.tasks.size)
        
        val outcome = if (effectiveQualityAccepted || synthesisRecoveryQueued) {
            WorkerOutcome.CONTINUE
        } else {
            WorkerOutcome.RETRY
        }
        
        diagnostics.info(
            "agent_milestone_finished",
            mapOf(
                "goal_id" to startedGoal.id,
                "task_id" to task.id,
                "outcome" to outcome.name,
                "quality_score" to result.completionScore,
                "duration_ms" to (finishedAt - attempt.startedAt),
                "source_count" to result.sources.size,
                "claim_count" to result.claims.size,
                "tool_execution_count" to result.toolExecutions.size,
                "synthesis_recovery_queued" to synthesisRecoveryQueued,
                "structured_output_repaired" to result.structuredOutputRepaired,
            ),
        )
        return outcome
    }

    private fun persistTaskFailure(
        goalId: String,
        taskId: String,
        agentAttemptId: String,
        error: Throwable,
        currentFingerprint: String? = null,
        ownership: ExecutionOwnership,
        models: List<OpenRouterModel> = emptyList(),
    ): WorkerOutcome {
        beforeCommitHook?.beforeCommit(goalId, taskId, ownership)
        val message = error.toAgentFailureMessage("The agent milestone failed.").take(1_000)
        val latest = store.loadSnapshot().goals.firstOrNull { it.id == goalId } ?: return WorkerOutcome.FAIL
        val currentTask = latest.tasks.firstOrNull { it.id == taskId } ?: return WorkerOutcome.FAIL
        val statusCode = (error as? OpenRouterException)?.statusCode
        val failureUsage = error as? OpenRouterException
        val descriptor = FailureClassifier.classify(
            error = error,
            statusCode = statusCode,
            goalId = goalId,
            taskId = taskId,
            operationId = "task_execution",
            emptyModelOutput = error.message?.contains("model returned no usable text") == true,
        )
        val decision = ProviderRecoveryPolicy.decideWithDescriptor(
            descriptor = descriptor,
            currentModelId = latest.executionModelId,
            routingStage = latest.routingStage,
            isFreeOnly = latest.freeOnly,
            isIntelligenceEscalation = (currentFingerprint != null && currentFingerprint != currentTask.lastEscalatedFingerprint)
        )
        
        val allocationRecovery = AgentResearchAllocator.recoveryStrategy(latest, currentTask, message)
        diagnostics.info(
            "research_allocation_recovery_strategy_changed",
            mapOf(
                "goal_id" to goalId,
                "task_id" to taskId,
                "strategy" to allocationRecovery.name,
                "reason" to descriptor.failureClass
            )
        )

        diagnostics.error(
            event = "agent_milestone_failed",
            throwable = error,
            fields = mapOf(
                "goal_id" to goalId,
                "task_id" to taskId,
                "http_status" to statusCode,
                "failure_class" to descriptor.failureClass,
                "accounted_failure_tokens" to failureUsage?.totalTokens,
                "accounted_failure_cost_usd" to failureUsage?.costUsd,
                "accounted_failure_web_searches" to failureUsage?.webSearchRequests,
                "recovery" to decision.action.name,
                "recovery_model" to decision.nextModelId,
            ),
        )
        var failureCommitted = false
        var automaticResearchWindowOpened = false
        var automaticCorrectionWindowOpened = false
        var automaticEvidenceBoundedWindowOpened = false
        var automaticSynthesisAnalysisFallbackOpened = false
        val updatedSnapshot = store.updateGoal(goalId) { current ->
            // Failures obey the same exact goal/task/attempt lease as successful
            // results. A cancelled or superseded call must not consume usage,
            // change routing, or overwrite the newer Worker's durable state.
            if (!canCommitMilestoneResult(current, taskId, agentAttemptId, ownership)) return@updateGoal current
            val currentTaskInUpdate = current.tasks.firstOrNull { it.id == taskId }
                ?: return@updateGoal current
            failureCommitted = true
            val updatedLease = if (current.executionLease?.workerId == ownership.workerId) {
                current.executionLease.copy(heartbeatAt = System.currentTimeMillis())
            } else {
                current.executionLease
            }
            val waiting = decision.action == ProviderRecoveryAction.WAIT_FOR_CREDENTIAL
            val waitingForNetwork = decision.action == ProviderRecoveryAction.WAIT_FOR_NETWORK
            val switchingModels = decision.action in setOf(
                ProviderRecoveryAction.SWITCH_TO_STABLE,
                ProviderRecoveryAction.SWITCH_TO_FREE,
                ProviderRecoveryAction.ESCALATE_TO_PAID,
            )

            var routedCurrent = current.accountAgentFailureUsage(error).recoverProviderRoute(
                decision = decision,
                failedRoute = ProviderRouteKind.EXECUTION,
            )
            val updatedCooldowns = if (statusCode == 429) {
                routedCurrent.modelCooldowns + (current.executionModelId to (System.currentTimeMillis() + 300_000L))
            } else {
                routedCurrent.modelCooldowns
            }

            if (switchingModels && models.isNotEmpty()) {
                val nextRoute = AgentModelSelector.choose(
                    models = models,
                    selectedModelId = if (decision.action == ProviderRecoveryAction.SWITCH_TO_FREE) {
                        ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID
                    } else null,
                    freeOnly = routedCurrent.freeOnly,
                    modelCooldowns = updatedCooldowns,
                    routingStage = routedCurrent.routingStage,
                )
                routedCurrent = routedCurrent.copy(executionModelId = nextRoute.executionModelId)
            }

            val attemptLimit = localAttemptWindowLimit(currentTaskInUpdate.capability)
            val attemptWindowExhausted = !waiting && !waitingForNetwork &&
                (attemptLimit != null) &&
                (currentTaskInUpdate.attemptCount >= attemptLimit)
            val automaticResearchRecovery = attemptWindowExhausted &&
                (currentTaskInUpdate.capability in AgentCapability.RESEARCH_CAPABILITIES)
            val automaticCorrectionRecovery = attemptWindowExhausted &&
                (currentTaskInUpdate.capability == AgentCapability.CORRECT)
            val automaticEvidenceBoundedRecovery = attemptWindowExhausted &&
                (currentTaskInUpdate.capability in AgentCapability.EVIDENCE_BOUNDED_CAPABILITIES)
            val automaticSynthesisAnalysisFallback = attemptWindowExhausted &&
                isSynthesisGapAnalysisTask(currentTaskInUpdate) &&
                (currentTaskInUpdate.capability == AgentCapability.TOOL_USE)
            automaticResearchWindowOpened = automaticResearchRecovery
            automaticCorrectionWindowOpened = automaticCorrectionRecovery
            automaticEvidenceBoundedWindowOpened = automaticEvidenceBoundedRecovery
            automaticSynthesisAnalysisFallbackOpened = automaticSynthesisAnalysisFallback
            val stopMessage = if (attemptWindowExhausted) {
                when {
                    automaticResearchRecovery -> automaticResearchRecoveryMessage(currentTaskInUpdate, message)
                    automaticCorrectionRecovery -> automaticCorrectionRecoveryMessage(currentTaskInUpdate, message)
                    automaticEvidenceBoundedRecovery ->
                        automaticEvidenceBoundedRecoveryMessage(currentTaskInUpdate, message)
                    automaticSynthesisAnalysisFallback ->
                        automaticSynthesisAnalysisFallbackMessage(currentTaskInUpdate, message)
                    else ->
                        "${currentTaskInUpdate.capability.wireName.replace('_', ' ').replaceFirstChar(Char::uppercase)} milestone reached its $attemptLimit-attempt local safety window after a provider failure. Preserved work remains available. Resume the goal to start a fresh bounded attempt window."
                }
            } else {
                null
            }
            val failureFinishedAt = System.currentTimeMillis()
            val cooldownDuration = if (statusCode == 429 && !switchingModels) 120_000L else null 

            routedCurrent.copy(
                status = when {
                    waiting -> AgentGoalStatus.WAITING_FOR_CREDENTIAL
                    waitingForNetwork -> AgentGoalStatus.WAITING_FOR_NETWORK
                    decision.action == ProviderRecoveryAction.ROUTE_EXHAUSTED -> AgentGoalStatus.BLOCKED
                    attemptWindowExhausted &&
                        !automaticResearchRecovery &&
                        !automaticCorrectionRecovery &&
                        !automaticEvidenceBoundedRecovery &&
                        !automaticSynthesisAnalysisFallback -> AgentGoalStatus.FAILED
                    else -> AgentGoalStatus.QUEUED
                },
                modelCooldowns = updatedCooldowns,
                executionLease = updatedLease,
                networkWaitStartedAt = if (waitingForNetwork) failureFinishedAt else routedCurrent.networkWaitStartedAt,
                networkWaitReason = if (waitingForNetwork) decision.explanation else routedCurrent.networkWaitReason,
                resumeStatusAfterNetwork = if (waitingForNetwork) AgentGoalStatus.QUEUED else routedCurrent.resumeStatusAfterNetwork,
                nextRetryAt = if (waitingForNetwork) failureFinishedAt + 30_000L else routedCurrent.nextRetryAt,
                tasks = routedCurrent.tasks.map { existing ->
                    if (existing.id == taskId) {
                        val failedTask = existing.copy(
                            status = if (decision.action == ProviderRecoveryAction.ROUTE_EXHAUSTED) AgentTaskStatus.FAILED else AgentTaskStatus.FAILED, // Both are FAILED, but goal status differs
                            attemptCount = if (statusCode == 429 && switchingModels) (existing.attemptCount - 1).coerceAtLeast(0) else existing.attemptCount,
                            lastError = stopMessage ?: message,
                            lastRequestFingerprint = currentFingerprint,
                            lastEscalatedFingerprint = if (decision.action == ProviderRecoveryAction.ESCALATE_TO_PAID) currentFingerprint else existing.lastEscalatedFingerprint,
                            finishedAt = failureFinishedAt,
                            automaticWindowReopenCount = if (automaticResearchRecovery || automaticCorrectionRecovery || automaticEvidenceBoundedRecovery || automaticSynthesisAnalysisFallback)
                                existing.automaticWindowReopenCount + 1 else existing.automaticWindowReopenCount,
                            cooldownUntil = if (decision.action == ProviderRecoveryAction.ROUTE_EXHAUSTED) failureFinishedAt + 300_000L else (cooldownDuration?.let { failureFinishedAt + it } ?: existing.cooldownUntil),
                            failureClass = if (waitingForNetwork) "network_resolution" else existing.failureClass,
                            waitReason = if (waitingForNetwork) decision.explanation else existing.waitReason,
                        )
                        when {
                            automaticResearchRecovery -> {
                                failedTask.reopenAutomaticResearchWindow(
                                    preciseFailure = message,
                                    madeMeaningfulProgress = false,
                                    now = failureFinishedAt,
                                )
                            }
                            automaticCorrectionRecovery -> {
                                failedTask.reopenAutomaticCorrectionWindow(
                                    preciseFailure = message,
                                    now = failureFinishedAt,
                                )
                            }
                            automaticEvidenceBoundedRecovery -> {
                                failedTask.reopenAutomaticEvidenceBoundedWindow(
                                    preciseFailure = message,
                                    now = failureFinishedAt,
                                )
                            }
                            automaticSynthesisAnalysisFallback -> {
                                failedTask.rerouteUnavailableSynthesisAnalysis(
                                    preciseFailure = message,
                                    now = failureFinishedAt,
                                )
                            }
                            else -> failedTask
                        }
                    } else {
                        existing
                    }
                },
                attempts = retainAttempts(
                    routedCurrent.attempts.map { existing ->
                        if (existing.id == agentAttemptId) {
                            existing.copy(
                                status = AgentAttemptStatus.FAILED,
                                finishedAt = failureFinishedAt,
                                role = failureUsage?.role,
                                selectionReason = failureUsage?.selectionReason,
                                previousRoute = failureUsage?.previousRoute,
                                cooldownState = failureUsage?.cooldownState,
                                provider = failureUsage?.provider,
                                finishReason = failureUsage?.finishReason,
                                nativeFinishReason = failureUsage?.nativeFinishReason,
                                promptTokens = failureUsage?.promptTokens,
                                completionTokens = failureUsage?.completionTokens,
                                totalTokens = failureUsage?.totalTokens,
                                costUsd = failureUsage?.costUsd,
                                webSearchRequests = failureUsage?.webSearchRequests,
                                error = message,
                            )
                        } else {
                            existing
                        }
                    },
                ),
                events = appendEvent(
                    routedCurrent.events,
                    stopMessage ?: "${decision.explanation} Last milestone error: $message",
                ),
                error = when {
                    waiting -> decision.explanation
                    attemptWindowExhausted &&
                        !automaticResearchRecovery &&
                        !automaticCorrectionRecovery &&
                        !automaticEvidenceBoundedRecovery &&
                        !automaticSynthesisAnalysisFallback -> stopMessage
                    else -> null
                },
            )
        }
        if (!failureCommitted) {
            diagnostics.warning(
                "agent_late_milestone_failure_discarded",
                mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "attempt_id" to agentAttemptId,
                ),
            )
            return WorkerOutcome.DONE
        }
        val persistedGoal = updatedSnapshot.goals.firstOrNull { it.id == goalId }
            ?: return WorkerOutcome.FAIL
        if (persistedGoal.status in setOf(
                AgentGoalStatus.PAUSED,
                AgentGoalStatus.CANCELLED,
                AgentGoalStatus.COMPLETED,
            )
        ) {
            return WorkerOutcome.DONE
        }
        if (automaticResearchWindowOpened) {
            diagnostics.warning(
                "agent_research_recovery_window_advanced_after_provider_failure",
                mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "attempt_limit" to MAX_RESEARCH_MILESTONE_ATTEMPTS,
                    "provider_recovery" to decision.action.name,
                    "recovery_model" to decision.nextModelId,
                ),
            )
        }
        if (automaticCorrectionWindowOpened) {
            diagnostics.warning(
                "agent_correction_recovery_window_advanced_after_provider_failure",
                mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "attempt_limit" to MAX_CORRECTION_MILESTONE_ATTEMPTS,
                    "provider_recovery" to decision.action.name,
                    "recovery_model" to decision.nextModelId,
                ),
            )
        }
        if (automaticEvidenceBoundedWindowOpened) {
            val recoveredTask = persistedGoal.tasks.firstOrNull { it.id == taskId }
            diagnostics.warning(
                "agent_evidence_bounded_recovery_window_advanced_after_provider_failure",
                mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "capability" to recoveredTask?.capability?.name,
                    "attempt_limit" to MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS,
                    "provider_recovery" to decision.action.name,
                    "recovery_model" to decision.nextModelId,
                ),
            )
        }
        if (automaticSynthesisAnalysisFallbackOpened) {
            diagnostics.warning(
                "agent_synthesis_analysis_rerouted_after_provider_failure",
                mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "tool_attempt_limit" to MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS,
                    "provider_recovery" to decision.action.name,
                    "recovery_model" to decision.nextModelId,
                ),
            )
        }
        if (persistedGoal.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
            return WorkerOutcome.DONE
        }
        if (persistedGoal.status == AgentGoalStatus.FAILED) {
            val persistedTask = persistedGoal.tasks.firstOrNull { it.id == taskId }
            diagnostics.warning(
                "agent_local_attempts_exhausted_after_provider_failure",
                mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "capability" to persistedTask?.capability?.name,
                    "attempt_limit" to persistedTask?.capability?.let(::localAttemptWindowLimit),
                    "provider_recovery" to decision.action.name,
                ),
            )
            return WorkerOutcome.DONE
        }
        if (
            automaticResearchWindowOpened ||
            automaticCorrectionWindowOpened ||
            automaticEvidenceBoundedWindowOpened ||
            automaticSynthesisAnalysisFallbackOpened
        ) {
            return WorkerOutcome.RETRY
        }
        return when (decision.action) {
            ProviderRecoveryAction.LOCAL_REPAIR -> WorkerOutcome.RETRY
            ProviderRecoveryAction.WAIT_FOR_CREDENTIAL -> WorkerOutcome.DONE
            ProviderRecoveryAction.WAIT_FOR_NETWORK -> WorkerOutcome.DONE
            ProviderRecoveryAction.ROUTE_EXHAUSTED -> WorkerOutcome.DONE
            ProviderRecoveryAction.SWITCH_TO_STABLE,
            ProviderRecoveryAction.SWITCH_TO_FREE,
            ProviderRecoveryAction.ESCALATE_TO_PAID,
            ProviderRecoveryAction.AFTER_MATERIAL_STRATEGY_CHANGE,
            -> WorkerOutcome.CONTINUE
            ProviderRecoveryAction.RETRY_CURRENT_ROUTE -> WorkerOutcome.RETRY
        }
    }

    private fun attachClaimsToEvidence(
        claims: List<AgentClaim>,
        evidenceItem: AgentEvidence,
        priorEvidence: List<AgentEvidence>,
    ): List<AgentClaim> {
        val allEvidence = priorEvidence + evidenceItem
        val validEvidenceIds = allEvidence.mapTo(mutableSetOf()) { it.id }
        val evidenceIdsBySourceUrl = buildMap {
            allEvidence.forEach { evidence ->
                evidence.sources.forEach { source ->
                    getOrPut(source.url) { mutableSetOf() }.add(evidence.id)
                }
            }
        }
        val sourceBackedEvidenceIds = allEvidence.asSequence()
            .filter { it.sources.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.id }
        return claims.map { claim ->
            val explicitlyReferencedEvidenceIds = claim.supportingEvidenceIds.filter(validEvidenceIds::contains)
            val evidenceIds = buildList {
                addAll(explicitlyReferencedEvidenceIds)
                claim.sourceUrls.forEach { sourceUrl ->
                    addAll(evidenceIdsBySourceUrl[sourceUrl].orEmpty())
                }
                if (
                    claim.type != AgentClaimType.FACT ||
                    evidenceItem.sources.isEmpty() ||
                    evidenceItem.sources.any { it.url in claim.sourceUrls }
                ) {
                    add(evidenceItem.id)
                }
            }.distinct()
            val resolvedSourceUrls = resolvePreciseClaimSourceUrls(
                explicitSourceUrls = claim.sourceUrls,
                referencedEvidenceIds = explicitlyReferencedEvidenceIds,
                evidence = allEvidence,
            )
            val hasMatchedSourceEvidence = evidenceIds.any { evidenceId ->
                evidenceId in sourceBackedEvidenceIds &&
                    allEvidence.firstOrNull { it.id == evidenceId }
                        ?.sources
                        ?.any { it.url in resolvedSourceUrls }
                        ?: false
            }
            val support = when {
                claim.support == AgentClaimSupport.CONTRADICTED -> claim.support
                (claim.type == AgentClaimType.FACT) && hasMatchedSourceEvidence -> AgentClaimSupport.SUPPORTED
                (claim.type == AgentClaimType.FACT) && evidenceIds.isNotEmpty() -> AgentClaimSupport.PARTIAL
                claim.support == AgentClaimSupport.UNSUPPORTED && evidenceIds.isNotEmpty() -> AgentClaimSupport.SUPPORTED
                else -> claim.support
            }
            repairOverAttributedClaim(
                claim = claim.copy(
                    supportingEvidenceIds = evidenceIds,
                    sourceUrls = resolvedSourceUrls,
                    support = support,
                ),
                evidence = allEvidence,
            )
        }
    }

    private fun buildEvidenceLinks(
        claims: List<AgentClaim>,
        currentEvidence: AgentEvidence,
        priorEvidence: List<AgentEvidence>,
    ): List<AgentEvidenceLink> {
        val validIds = priorEvidence.mapTo(mutableSetOf()) { it.id }.apply { add(currentEvidence.id) }
        return claims.flatMap { claim ->
            claim.supportingEvidenceIds.asSequence()
                .filter(validIds::contains)
                .map { evidenceId ->
                    AgentEvidenceLink(
                        claimId = claim.id,
                        evidenceId = evidenceId,
                        relation = when (claim.support) {
                            AgentClaimSupport.CONTRADICTED -> AgentEvidenceRelation.CONTRADICTS
                            AgentClaimSupport.PARTIAL -> AgentEvidenceRelation.QUALIFIES
                            else -> AgentEvidenceRelation.SUPPORTS
                        },
                    )
                }
                .toList()
        }
    }

    private fun mergeClaims(existing: List<AgentClaim>, incoming: List<AgentClaim>): List<AgentClaim> {
        val merged = (existing + incoming).associateBy { claim ->
            claim.text.normalizedClaimText()
        }.values.toList()
        return merged
    }

    private fun mergePartialEvidence(previous: AgentEvidence, incoming: AgentEvidence): AgentEvidence {
        val richer = if (incoming.content.length >= previous.content.length) incoming else previous
        return richer.copy(
            id = previous.id,
            taskId = previous.taskId ?: incoming.taskId,
            kind = incoming.kind,
            title = incoming.title,
            sources = (previous.sources + incoming.sources).distinctBy { it.url },
            createdAt = previous.createdAt,
        )
    }

    private fun commitTaskResult(
        current: AgentGoal,
        task: AgentTask,
        attemptId: String,
        result: AgentStepResult,
        quality: StepQualityEvaluation,
        synthesisGapDecision: SynthesisGapDecision,
        finishedAt: Long,
        proposedEvidenceItem: AgentEvidence,
        evidenceKind: AgentEvidenceKind,
        currentFingerprint: String,
        ownership: ExecutionOwnership,
    ): AgentGoal {
        if (!canCommitMilestoneResult(current, task.id, attemptId, ownership)) return current
        val currentTask = current.tasks.firstOrNull { it.id == task.id } ?: return current

        val stallDiagnosis = detectExecutionStall(current, currentTask, result, quality.passed)
        val decision = if (stallDiagnosis != ExecutionStallDiagnosis.NONE) {
            ProviderRecoveryPolicy.decide(
                statusCode = null,
                currentModelId = current.executionModelId,
                routingStage = current.routingStage,
                intelligenceWallReached = stallDiagnosis == ExecutionStallDiagnosis.INTELLIGENCE_WALL,
                progressStallFailure = stallDiagnosis == ExecutionStallDiagnosis.PROGRESS_STALL,
                repetitiveSearchStall = stallDiagnosis == ExecutionStallDiagnosis.REPETITIVE_SEARCH_STALL,
                shallowResearchStall = stallDiagnosis == ExecutionStallDiagnosis.SHALLOW_RESEARCH_STALL,
                verificationCircularity = stallDiagnosis == ExecutionStallDiagnosis.VERIFICATION_CIRCULARITY,
                isFreeOnly = current.freeOnly,
                isIntelligenceEscalation = (currentFingerprint != currentTask.lastEscalatedFingerprint)
            )
        } else null

        val completedSynthesisRecoveries = completedSynthesisGapRecoveryPasses(current)
        val routedCurrent = if (decision != null) {
            current.recoverProviderRoute(decision, ProviderRouteKind.EXECUTION)
        } else {
            current
        }

        val budgetExhausted = completedSynthesisRecoveries >= MAX_SYNTHESIS_GAP_RECOVERY_PASSES
        val synthesisRecoveryQueued = !quality.passed &&
            synthesisGapDecision.hasActionableGap &&
            !budgetExhausted
            
        // Advance to bounded publication if we qualify, OR if budget is exhausted 
        // and we have a substantial result that shouldn't be stranded in a loop.
        val boundedSynthesisAccepted = !quality.passed && budgetExhausted &&
            (synthesisGapDecision.qualifiesForBoundedPublication || synthesisGapDecision.hasActionableGap)

        val effectiveQualityAccepted = quality.passed || boundedSynthesisAccepted
        val priorTaskEvidence = routedCurrent.evidence.lastOrNull { evidence ->
            evidence.taskId == task.id && evidence.kind == evidenceKind
        }
        val candidateEvidenceItem = if (!effectiveQualityAccepted && (priorTaskEvidence != null)) {
            mergePartialEvidence(priorTaskEvidence, proposedEvidenceItem)
        } else {
            proposedEvidenceItem
        }

        val priorSourceUrls = priorTaskEvidence?.sources?.mapTo(mutableSetOf()) { it.url }.orEmpty()
        val priorClaimTexts = routedCurrent.claims.asSequence()
            .filter { it.taskId == task.id }
            .map { it.text.normalizedClaimText() }
            .toSet()

        val candidateClaimsForTask = if (task.capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)) {
            refineImpreciseClaimSourceSelections(
                claims = attachClaimsToEvidence(result.claims, candidateEvidenceItem, routedCurrent.evidence),
                evidence = routedCurrent.evidence + candidateEvidenceItem,
            )
        } else {
            attachClaimsToEvidence(result.claims, candidateEvidenceItem, routedCurrent.evidence)
        }

        val addedSubstantiveSources = result.sources.any { s -> 
            s.url !in priorSourceUrls && result.toolExecutions.any { it.toolName == "public_web_fetch" && it.succeeded && it.summary.contains(s.url) && !it.summary.contains("Rejected") }
        }
        val addedFactualClaims = candidateClaimsForTask.any { c -> 
            c.type == AgentClaimType.FACT && c.support == AgentClaimSupport.SUPPORTED && c.text.normalizedClaimText() !in priorClaimTexts 
        }
        val resolvedCriterion = currentTask.acceptanceChecks.any { it.status == AgentAcceptanceCheckStatus.PASS } && !task.acceptanceChecks.any { it.status == AgentAcceptanceCheckStatus.PASS }

        val madeMeaningfulProgress = effectiveQualityAccepted || synthesisRecoveryQueued ||
            addedSubstantiveSources || addedFactualClaims || resolvedCriterion

        val correctionAttemptWindowExhausted = hasExhaustedCorrectionAttemptWindow(currentTask, effectiveQualityAccepted)
        val requiredToolAttemptWindowExhausted =
            hasExhaustedRequiredToolAttemptWindow(currentTask, quality.toolUseGatePassed)
        val evidenceBoundedAttemptWindowExhausted =
            hasExhaustedEvidenceBoundedAttemptWindow(currentTask, effectiveQualityAccepted)
        val researchAttemptWindowExhausted = hasExhaustedResearchAttemptWindow(currentTask, effectiveQualityAccepted)

        val automaticCorrectionRecovery = (correctionAttemptWindowExhausted && !synthesisRecoveryQueued)
        val automaticEvidenceBoundedRecovery = (evidenceBoundedAttemptWindowExhausted && !synthesisRecoveryQueued)
        val automaticSynthesisAnalysisFallback = (requiredToolAttemptWindowExhausted && isSynthesisGapAnalysisTask(currentTask))

        val localAttemptWindowExhausted = (!synthesisRecoveryQueued && !automaticSynthesisAnalysisFallback && (
            correctionAttemptWindowExhausted || requiredToolAttemptWindowExhausted ||
                evidenceBoundedAttemptWindowExhausted || researchAttemptWindowExhausted
            ))

        val qualityError = if (quality.passed) null else quality.reasons.joinToString(" ").take(2_000)
        val localStopMessage = when {
            automaticSynthesisAnalysisFallback ->
                automaticSynthesisAnalysisFallbackMessage(currentTask, qualityError.orEmpty())
            correctionAttemptWindowExhausted ->
                automaticCorrectionRecoveryMessage(currentTask, qualityError.orEmpty())
            evidenceBoundedAttemptWindowExhausted ->
                automaticEvidenceBoundedRecoveryMessage(currentTask, qualityError.orEmpty())
            requiredToolAttemptWindowExhausted && (currentTask.capability == AgentCapability.TOOL_CREATE) ->
                "Tool Foundry milestone did not both create and exercise a tested recipe after $MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS bounded attempts. Preserved work remains available. Resume the goal to start a fresh bounded tool-attempt window."
            requiredToolAttemptWindowExhausted ->
                "Tool-use milestone did not complete a successful local tool call after $MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS bounded attempts. Preserved work remains available. Resume the goal to start a fresh bounded tool-attempt window."
            researchAttemptWindowExhausted -> automaticResearchRecoveryMessage(currentTask, qualityError.orEmpty())
            else -> null
        }

        val evidenceItem = if (!madeMeaningfulProgress && (priorTaskEvidence != null)) priorTaskEvidence else candidateEvidenceItem
        val claimsForTask = if (!madeMeaningfulProgress && (priorTaskEvidence != null)) emptyList() else candidateClaimsForTask

        val replacePublicationClaims = effectiveQualityAccepted && replacesPublicationClaimGraph(task.capability)
        val replaceTaskClaims = effectiveQualityAccepted && !replacePublicationClaims
        val oldClaimIds = when {
            replacePublicationClaims -> current.claims.mapTo(mutableSetOf()) { it.id }
            replaceTaskClaims -> current.claims.asSequence().filter { it.taskId == task.id }.mapTo(mutableSetOf()) { it.id }
            else -> mutableSetOf()
        }
        val claimBase = when {
            replacePublicationClaims -> emptyList()
            replaceTaskClaims -> current.claims.filterNot { it.taskId == task.id }
            else -> current.claims
        }
        val mergedClaims = mergeClaims(claimBase, claimsForTask)
        val retainedClaimIds = mergedClaims.mapTo(mutableSetOf()) { it.id }
        val retainedLinks = current.evidenceLinks.asSequence()
            .filterNot { it.claimId in oldClaimIds }
            .filter { it.claimId in retainedClaimIds }
            .toList()
        val newLinks = buildEvidenceLinks(claimsForTask, evidenceItem, current.evidence)

        val baseUpdatedTasks = routedCurrent.tasks.map { existing ->
            if (existing.id == task.id) {
                val updatedTask = existing.copy(
                    status = if (effectiveQualityAccepted) AgentTaskStatus.COMPLETED else AgentTaskStatus.FAILED,
                    lastError = qualityError.takeUnless { effectiveQualityAccepted },
                    acceptanceChecks = if (!effectiveQualityAccepted && (!madeMeaningfulProgress || existing.progressScore > result.completionScore)) {
                        existing.acceptanceChecks
                    } else {
                        result.acceptanceChecks
                    },
                    progressScore = if (effectiveQualityAccepted) {
                        result.completionScore.coerceIn(0.0, 1.0)
                    } else {
                        maxOf(existing.progressScore, result.completionScore).coerceIn(0.0, 1.0)
                    },
                    automaticWindowReopenCount = if (effectiveQualityAccepted) 0 else existing.automaticWindowReopenCount,
                    lastRequestFingerprint = currentFingerprint,
                    lastEscalatedFingerprint = if (decision?.action == ProviderRecoveryAction.ESCALATE_TO_PAID) currentFingerprint else existing.lastEscalatedFingerprint,
                    progressFingerprint = currentFingerprint,
                    queryFingerprints = (existing.queryFingerprints + result.queryFingerprints).distinct(),
                    recentQueryFingerprints = (existing.recentQueryFingerprints + result.queryFingerprints).takeLast(10),
                    rejectedQueries = (existing.rejectedQueries + result.rejectedQueries).takeLast(20),
                    consecutiveNoProgressCount = if (madeMeaningfulProgress) 0 else existing.consecutiveNoProgressCount + 1,
                    lastMaterialProgressAt = if (madeMeaningfulProgress) finishedAt else existing.lastMaterialProgressAt,
                    lastMaterialProgressFingerprint = if (madeMeaningfulProgress) currentFingerprint else existing.lastMaterialProgressFingerprint,
                    finishedAt = finishedAt,
                    outputEvidenceId = evidenceItem.id,
                    failureClass = if (effectiveQualityAccepted) null else existing.failureClass,
                    waitReason = if (effectiveQualityAccepted) null else existing.waitReason,
                    waitCondition = if (effectiveQualityAccepted) null else existing.waitCondition,
                    cooldownUntil = if (effectiveQualityAccepted) null else existing.cooldownUntil,
                )
                val exhausted = !effectiveQualityAccepted && updatedTask.consecutiveNoProgressCount >= 5
                val finalUpdatedTask = if (exhausted) {
                    updatedTask.copy(
                        status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
                        branchExhaustionReason = "No measurable progress after 5 consecutive attempts.",
                        branchExhaustedAt = finishedAt
                    )
                } else updatedTask

                when {
                    researchAttemptWindowExhausted -> finalUpdatedTask.reopenAutomaticResearchWindow(qualityError.orEmpty(), madeMeaningfulProgress, finishedAt)
                    automaticCorrectionRecovery -> finalUpdatedTask.reopenAutomaticCorrectionWindow(qualityError.orEmpty(), finishedAt)
                    automaticEvidenceBoundedRecovery -> finalUpdatedTask.reopenAutomaticEvidenceBoundedWindow(qualityError.orEmpty(), finishedAt)
                    automaticSynthesisAnalysisFallback -> finalUpdatedTask.rerouteUnavailableSynthesisAnalysis(qualityError.orEmpty(), finishedAt)
                    else -> finalUpdatedTask
                }
            } else {
                existing
            }
        }

        val updatedTasks = if (synthesisRecoveryQueued) {
            insertSynthesisGapRecovery(
                goal = routedCurrent.copy(tasks = baseUpdatedTasks),
                synthesisTaskId = task.id,
                decision = synthesisGapDecision,
                preciseFailure = qualityError.orEmpty(),
                now = finishedAt,
            ).tasks
        } else {
            baseUpdatedTasks
        }

        val nextStatus = when {
            decision != null -> decision.nextGoalStatus(routedCurrent.status)
            synthesisRecoveryQueued -> AgentGoalStatus.QUEUED
            boundedSynthesisAccepted -> AgentGoalStatus.VERIFYING
            effectiveQualityAccepted -> AgentGoalStatus.QUEUED
            automaticCorrectionRecovery || automaticEvidenceBoundedRecovery || automaticSynthesisAnalysisFallback -> AgentGoalStatus.QUEUED
            localAttemptWindowExhausted && !researchAttemptWindowExhausted && !automaticCorrectionRecovery && !automaticEvidenceBoundedRecovery -> AgentGoalStatus.FAILED
            else -> AgentGoalStatus.QUEUED
        }

        val nextGoal = routedCurrent.copy(
            status = nextStatus,
            blockedReason = if (nextStatus == AgentGoalStatus.BLOCKED) "PARTIAL_EVIDENCE_BOUNDARY" else null,
            tasks = updatedTasks,
            attempts = retainAttempts(
                routedCurrent.attempts.map { existing ->
                    if (existing.id == attemptId) {
                        existing.copy(
                            status = if (effectiveQualityAccepted) AgentAttemptStatus.SUCCEEDED else AgentAttemptStatus.FAILED,
                            finishedAt = finishedAt,
                            resolvedModel = result.summary.resolvedModel,
                            role = result.summary.role,
                            selectionReason = result.summary.selectionReason,
                            previousRoute = result.summary.previousRoute,
                            cooldownState = result.summary.cooldownState,
                            provider = result.summary.provider,
                            finishReason = result.summary.finishReason,
                            nativeFinishReason = result.summary.nativeFinishReason,
                            responseId = result.summary.responseId,
                            promptTokens = result.summary.promptTokens,
                            completionTokens = result.summary.completionTokens,
                            totalTokens = result.summary.totalTokens,
                            costUsd = result.summary.costUsd,
                            webSearchRequests = result.summary.webSearchRequests,
                            webFetchRequests = result.summary.webFetchRequests,
                            discoveredLeads = result.summary.discoveredLeads,
                            rabbitHoleIterations = result.summary.rabbitHoleIterations,
                            error = qualityError.takeUnless { effectiveQualityAccepted },
                        )
                    } else {
                        existing
                    }
                },
            ),
            evidence = upsertEvidence(routedCurrent.evidence, evidenceItem),
            sourceReads = (routedCurrent.sourceReads + result.sources.map { s ->
                SourceRead(
                    id = UUID.randomUUID().toString(),
                    url = s.url,
                    canonicalUrl = ResearchQualityGate.canonicalSourceUrl(s.url),
                    httpCode = 200, // Assumed successful if in result.sources
                    contentType = "text/html",
                    content = s.excerpt.orEmpty(),
                    sourceRole = researchPassRole(task).name,
                    authorityScore = computeDomainAuthorityScore(s.url, s.excerpt.orEmpty())
                )
            }).distinctBy { it.url },
            claims = mergedClaims,
            evidenceLinks = retainEvidenceLinks(
                (retainedLinks + newLinks)
                    .filter { it.claimId in retainedClaimIds }
                    .distinctBy { Triple(it.claimId, it.evidenceId, it.relation) },
            ),
            events = appendEvent(
                routedCurrent.events,
                when {
                    decision != null -> "${decision.explanation} Milestone will retry with improved model intelligence."
                    synthesisRecoveryQueued -> "Synthesis exposed a concrete evidence gap. Recovery round queued before synthesis resumes."
                    boundedSynthesisAccepted -> boundedSynthesisEventMessage(currentTask, synthesisGapDecision)
                    quality.boundedResearchRecoveryAccepted -> boundedResearchRecoveryEventMessage(currentTask)
                    localAttemptWindowExhausted -> localStopMessage.orEmpty()
                    effectiveQualityAccepted -> "Completed milestone ${task.order + 1}: ${task.title} (${result.completionScore.asPercent()})."
                    !quality.passed && madeMeaningfulProgress -> "Milestone ${task.order + 1} produced new partial work and will refine automatically: $qualityError"
                    !quality.passed -> "Milestone ${task.order + 1} produced no new verified progress. The strongest checkpoint was retained: $qualityError"
                    else -> "Milestone ${task.order + 1} failed its quality gate: $qualityError"
                },
            ),
            error = if (decision != null || researchAttemptWindowExhausted || automaticCorrectionRecovery ||
                automaticEvidenceBoundedRecovery || automaticSynthesisAnalysisFallback ||
                synthesisRecoveryQueued || boundedSynthesisAccepted) null else localStopMessage,
        )
        
        val accountingKey = "task_accounting_$attemptId"
        val baseGoal = if (routedCurrent.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) {
            nextGoal
        } else {
            val updated = nextGoal.withAdditionalUsage(result.summary.totalTokens, result.summary.costUsd)
            val record = IdempotencyRecord(
                key = accountingKey,
                effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
                state = IdempotencyState.COMMITTED,
                claimOwner = ownership.workerId,
                committedAt = System.currentTimeMillis(),
                completedBy = ownership.workerId
            )
            updated.copy(idempotencyRecords = updated.idempotencyRecords + record)
        }

        return if (madeMeaningfulProgress) {
            val checkpoint = AgentCheckpoint(
                sequence = baseGoal.checkpoints.size + 1,
                completedTaskIds = updatedTasks.asSequence().filter { it.status == AgentTaskStatus.COMPLETED }.map { it.id }.toList(),
                progressScore = baseGoal.denseProgressScore,
                note = when {
                    boundedSynthesisAccepted -> "Milestone '${task.title}' produced a grounded, explicitly bounded conclusion."
                    quality.boundedResearchRecoveryAccepted -> "Milestone '${task.title}' established a durable evidence boundary."
                    effectiveQualityAccepted -> "Milestone '${task.title}' passed its local completion gate."
                    synthesisRecoveryQueued -> "Milestone '${task.title}' exposed a concrete evidence gap."
                    automaticSynthesisAnalysisFallback -> "Milestone '${task.title}' rerouted to deep-research fallback."
                    else -> "Milestone '${task.title}' preserved new partial progress."
                },
            )
            baseGoal.copy(checkpoints = appendCheckpoint(baseGoal.checkpoints, checkpoint))
        } else {
            baseGoal
        }
    }

    companion object {
        private const val MAX_SYNTHESIS_GAP_RECOVERY_PASSES = 3
    }
}
