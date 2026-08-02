package com.david.openassistant.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.security.ApiKeyStore
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AgentGoalWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val workerId = UUID.randomUUID().toString()
    private val store = AgentStore(appContext)
    private val diagnostics = RuntimeDiagnostics(appContext)
    private val researchMonitor = ResearchMonitor(appContext)
    private val keyStore = ApiKeyStore(appContext)
    private val toolRuntime = AutonomousToolRuntime(appContext)
    private val autonomyPolicy = AutonomyPolicy.DEFAULT
    private val client = AgentOpenRouterClient(
        toolRuntime = toolRuntime,
        autonomyPolicy = autonomyPolicy,
        researchMonitor = researchMonitor,
        diagnostics = diagnostics,
        store = store,
    )
    private val scheduler = AgentScheduler(appContext)

    private val planner = AgentPlanner(client, store, diagnostics)
    private val taskExecutor = AgentTaskExecutor(client, store, diagnostics, autonomyPolicy)
    private val verifier = AgentVerifier(client, store, diagnostics, autonomyPolicy)
    private val notifier = MissionForegroundNotifier(appContext)

    override suspend fun doWork(): Result {
        val goalId = inputData.getString(KEY_GOAL_ID) ?: run {
            diagnostics.warning("agent_worker_missing_goal_id")
            return Result.failure()
        }
        diagnostics.debug("WorkManager wake-up for goal $goalId")
        
        // Ensure initial notification state if we're not yet in the lock
        val initialGoal = findGoal(goalId)
        if (initialGoal != null && !initialGoal.status.isInactive()) {
            runCatching {
                setForeground(notifier.createForegroundInfo(goalId, initialGoal.title, initialGoal.status.name))
            }
        }

        return AgentGoalExecutionGate.withGoalLock(goalId) {
            executeGoalWorker(goalId)
        }
    }

    private suspend fun executeGoalWorker(cancellationGoalId: String): Result {
        val cancellationSignalled = AtomicBoolean(false)
        fun cancelActiveCalls(signalSource: String) {
            val goalId = cancellationGoalId
            if (!cancellationSignalled.compareAndSet(false, true)) return
            client.cancelActiveCalls()
            toolRuntime.cancelActiveCalls()
            researchMonitor.record(
                category = "mission",
                event = "worker_cancel_signal_received",
                level = "WARN",
                correlationId = goalId,
                targetSessionId = client.currentSessionId,
                fields = mapOf(
                    "goal_id" to goalId,
                    "signal_source" to signalSource,
                    "provider_calls_cancelled" to true,
                    "tool_network_calls_cancelled" to true,
                ),
            )
            diagnostics.warning(
                "agent_worker_cancel_signal_received",
                mapOf("goal_id" to goalId, "signal_source" to signalSource),
            )
        }
        val cancellationRegistration = AgentCallCancellationRegistry.register(cancellationGoalId) {
            cancelActiveCalls("goal_scheduler")
        }

        try {
            return withContext(Dispatchers.IO) {
                coroutineScope {
                    val goalId = cancellationGoalId
                    val goalDiagnostics = diagnostics.withContext(mapOf("goal_id" to goalId))
                    val workerStartedAt = System.currentTimeMillis()
                    
                    diagnostics.info(
                        event = "worker_started",
                        component = "worker",
                        fields = mapOf(
                            "goal_id" to goalId,
                            "worker_id" to workerId,
                            "run_attempt" to runAttemptCount
                        )
                    )

                    val initialGoal = findGoal(goalId) ?: run {
                        goalDiagnostics.warning("agent_worker_goal_not_found")
                        reconcileMissingGoal(goalId)
                        return@coroutineScope Result.failure()
                    }
                    if (initialGoal.status == AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION ||
                        initialGoal.userRequest.isBlank() || initialGoal.conversationId.isBlank()
                    ) {
                        goalDiagnostics.error(
                            "corrupt_or_incomplete_mission_blocked",
                            IllegalStateException("Mission provenance is incomplete; provider work was blocked."),
                            mapOf(
                                "status" to initialGoal.status.name,
                                "has_request" to initialGoal.userRequest.isNotBlank(),
                                "has_conversation_id" to initialGoal.conversationId.isNotBlank(),
                            ),
                        )
                        return@coroutineScope Result.failure()
                    }

                    // Canonical controlled stale-exchange reconciliation under goal lease lock
                    ProviderRequestLedger.reconcileStaleExchanges(store, goalId, workerId)

                    val now = System.currentTimeMillis()
                    val resumedGoal = if (initialGoal.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                        if (initialGoal.nextRetryAt == null || now >= initialGoal.nextRetryAt) {
                            store.updateGoal(goalId) { current ->
                                if (current.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                                    current.copy(
                                        status = current.resumeStatusAfterNetwork ?: AgentGoalStatus.QUEUED,
                                        networkRetryCount = current.networkRetryCount + 1,
                                        events = appendEvent(current.events, "Automatically resumed mission after network wait.")
                                    )
                                } else current
                            }.goals.firstOrNull { it.id == goalId } ?: return@coroutineScope Result.failure()
                        } else {
                            goalDiagnostics.info(
                                "agent_worker_waiting_for_network_retry",
                                mapOf("next_retry" to initialGoal.nextRetryAt)
                            )
                            return@coroutineScope Result.retry()
                        }
                    } else {
                        initialGoal
                    }

                    if (resumedGoal.status.isInactive()) {
                        goalDiagnostics.info(
                            "agent_worker_skipped_inactive_goal",
                            mapOf("status" to resumedGoal.status.name),
                        )
                        return@coroutineScope Result.success()
                    }

                    val allocationProfile = AgentResearchAllocator.profileForGoal(resumedGoal, autonomyPolicy)
                    val gaps = AgentResearchAllocator.evaluateGaps(resumedGoal, allocationProfile)
                    val allocationSelection = AgentResearchAllocator.chooseNextTask(resumedGoal, allocationProfile, now)

                    researchMonitor.record(
                        category = "allocation",
                        event = "research_allocation_profile_created",
                        correlationId = goalId,
                        fields = mapOf(
                            "goal_id" to goalId,
                            "complexity" to allocationProfile.complexity.name,
                            "risk" to allocationProfile.risk.name,
                            "passes" to allocationProfile.targetResearchPasses,
                            "sources" to allocationProfile.targetDistinctSources
                        )
                    )

                    if (gaps.remainingSourceGap > 0 || gaps.remainingDomainGap > 0 || gaps.remainingPrimarySourceGap || gaps.remainingContradictionGap) {
                        researchMonitor.record(
                            category = "allocation",
                            event = "research_allocation_gap_detected",
                            correlationId = goalId,
                            fields = mapOf(
                                "goal_id" to goalId,
                                "source_gap" to gaps.remainingSourceGap,
                                "domain_gap" to gaps.remainingDomainGap,
                                "primary_gap" to gaps.remainingPrimarySourceGap,
                                "contradiction_gap" to gaps.remainingContradictionGap
                            )
                        )
                    }

                    if (allocationSelection.taskId != null) {
                        researchMonitor.record(
                            category = "allocation",
                            event = "research_allocation_next_task_selected",
                            correlationId = goalId,
                            fields = mapOf(
                                "goal_id" to goalId,
                                "task_id" to allocationSelection.taskId,
                                "reason" to allocationSelection.reason
                            )
                        )
                    }

                    if (allocationSelection.taskId == null && allocationSelection.retryAfterCooldown) {
                        goalDiagnostics.info("agent_worker_all_runnable_tasks_in_cooldown")
                        return@coroutineScope Result.retry()
                    }

                    if (allocationSelection.taskId == null) {
                        // If no runnable task exists, and goal is NOT already waiting for network or planning, 
                        // check if all dependency-satisfied tasks are waiting for network or exhausted.
                        if (resumedGoal.status != AgentGoalStatus.WAITING_FOR_NETWORK && resumedGoal.status != AgentGoalStatus.PLANNING) {
                            val completedIds = resumedGoal.tasks.filter { it.status == AgentTaskStatus.COMPLETED }.map { it.id }.toSet()
                            val satisfiedTasks = resumedGoal.tasks.filter { it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.CANCELLED && it.dependsOn.all(completedIds::contains) }
                            
                            val allBlockedOrExhausted = satisfiedTasks.all { it.failureClass == "network_resolution" || it.branchExhaustionReason != null || it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }
                            
                            if (allBlockedOrExhausted && satisfiedTasks.isNotEmpty()) {
                                store.updateGoal(goalId) { current ->
                                    if (current.tasks.any { it.branchExhaustionReason != null || it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }) {
                                        current.copy(status = AgentGoalStatus.INSUFFICIENT_CURRENT_DATA)
                                    } else {
                                        current.copy(status = AgentGoalStatus.WAITING_FOR_NETWORK)
                                    }
                                }
                                return@coroutineScope Result.success()
                            } else if (satisfiedTasks.isEmpty() && resumedGoal.tasks.any { it.status == AgentTaskStatus.FAILED }) {
                                // If we have failed tasks that are blocked by dependencies or other reasons, repair them.
                                repairBlockedWorkflow(resumedGoal)
                            } else if (satisfiedTasks.isEmpty() && !resumedGoal.isReadyForVerification) {
                                 // Stranded: no runnable task, no satisfied tasks, and not ready for verification.
                                 store.updateGoal(goalId) { current ->
                                     current.copy(
                                         status = AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
                                         events = appendEvent(current.events, "Mission stranded: no runnable milestones remaining and research is exhausted.")
                                     )
                                 }
                                 return@coroutineScope Result.success()
                            }
                        }
                    }

                    if (resumedGoal.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                        // Waiting worker should not hold the lease
                        return@coroutineScope Result.success()
                    }

                    val acquisition = store.acquireLeaseAtomic(goalId, workerId, allocationSelection.taskId)
                    val leasedGoal = when (acquisition) {
                        is LeaseAcquisitionResult.Acquired -> acquisition.goal
                        is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.goal
                        is LeaseAcquisitionResult.LiveOwnerPresent -> {
                            goalDiagnostics.info("agent_worker_exit_live_owner_present")
                            return@coroutineScope Result.success()
                        }
                        is LeaseAcquisitionResult.RetryRequired -> {
                            goalDiagnostics.warning("agent_worker_lease_retry_required")
                            return@coroutineScope Result.retry()
                        }
                        is LeaseAcquisitionResult.MissionTerminal -> {
                            goalDiagnostics.info("agent_worker_exit_mission_terminal")
                            return@coroutineScope Result.success()
                        }
                        is LeaseAcquisitionResult.StorageFailure -> {
                            goalDiagnostics.error("agent_worker_lease_storage_failure", acquisition.cause)
                            return@coroutineScope Result.retry()
                        }
                    }

                    val lease = leasedGoal.executionLease!!
                    val workerStartTime = System.currentTimeMillis()
                    val heartbeatJob = launch {
                        while (isActive) {
                            delay(HEARTBEAT_INTERVAL_MS)
                            
                            // Safety: dataSync FGS has a 6-hour limit on Android 15+. 
                            // Checkpoint and yield before reaching the platform boundary.
                            if (System.currentTimeMillis() - workerStartTime > 5.5 * 3600_000L) {
                                goalDiagnostics.warning("fgs_duration_limit_approaching")
                                cancelActiveCalls("fgs_duration_limit")
                                break
                            }

                            val result = store.refreshExecutionLease(
                                goalId = goalId,
                                workerId = workerId,
                                attemptId = lease.attemptId,
                                generation = lease.generation,
                                taskId = lease.taskId
                            )
                            if (result != RefreshLeaseResult.Refreshed) {
                                diagnostics.warning(
                                    event = "lease_heartbeat_failed",
                                    component = "lease",
                                    fields = mapOf(
                                        "goal_id" to goalId,
                                        "worker_id" to workerId,
                                        "result" to result.toString()
                                    )
                                )
                                cancelActiveCalls("heartbeat_ownership_lost")
                                break
                            }
                            
                            // Sample healthy heartbeat every 5 iterations (~2.5 minutes)
                            if (heartbeatCount++ % 5 == 0) {
                                diagnostics.info(
                                    event = "lease_heartbeat_sampled",
                                    component = "lease",
                                    fields = mapOf(
                                        "goal_id" to goalId,
                                        "worker_id" to workerId,
                                        "lease_gen" to lease.generation
                                    )
                                )
                            }
                        }
                    }

                    try {
                        if (!researchMonitor.isActive()) {
                            runCatching { researchMonitor.start() }
                                .onFailure { error ->
                                    goalDiagnostics.error(
                                        "research_monitor_auto_start_failed",
                                        error,
                                    )
                                }
                        }
                        client.currentSessionId = researchMonitor.status().sessionId
                        val leasedNextTask = AgentResearchAllocator.chooseNextTask(leasedGoal, allocationProfile, now).taskId?.let { id -> leasedGoal.tasks.firstOrNull { it.id == id } }
                        researchMonitor.record(
                            category = "mission",
                            event = "worker_attached",
                            correlationId = goalId,
                            targetSessionId = client.currentSessionId,
                            fields = mapOf(
                                "goal_id" to goalId,
                                "worker_id" to workerId,
                                "run_attempt" to runAttemptCount,
                                "goal_status" to leasedGoal.status.name,
                                "user_request" to leasedGoal.userRequest,
                                "goal_title" to leasedGoal.title,
                                "current_task_id" to leasedNextTask?.id,
                                "allocation_profile" to allocationProfile.complexity.name,
                                "allocation_reason" to allocationSelection.reason,
                                "task_states" to leasedGoal.tasks.joinToString(",") {
                                    "${it.id}:${it.status.name}:${it.attemptCount}"
                                },
                                "attempt_count" to leasedGoal.attempts.size,
                                "evidence_count" to leasedGoal.evidence.size,
                                "claim_count" to leasedGoal.claims.size,
                                "checkpoint_count" to leasedGoal.checkpoints.size,
                                "total_tokens" to leasedGoal.totalTokens,
                                "total_cost_usd" to leasedGoal.totalCostUsd,
                                "last_error" to leasedGoal.error,
                            ),
                        )

                        val apiKey = keyStore.load() ?: run {
                            waitForCredential(goalId, "Waiting for a valid OpenRouter credential. All mission state is preserved.")
                            return@coroutineScope Result.success()
                        }

                        val models = runCatching { client.fetchModels(apiKey) }.getOrDefault(emptyList())

                        val outcome = if (leasedGoal.status == AgentGoalStatus.PLANNING) {
                            notifier.updateNotification(goalId, leasedGoal.title, "Planning")
                            planner.plan(apiKey, leasedGoal, models)
                        } else {
                            reconcileInterruptedWork(goalId)
                            val acquisition = store.acquireLeaseAtomic(goalId, workerId, null)
                            val goal = when (acquisition) {
                                is LeaseAcquisitionResult.Acquired -> acquisition.goal
                                is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.goal
                                else -> return@coroutineScope Result.success()
                            }
                            if (goal.status.isInactive()) return@coroutineScope Result.success()
                            val taskToExecute = AgentResearchAllocator.chooseNextTask(goal, allocationProfile, now).taskId?.let { id -> goal.tasks.firstOrNull { it.id == id } }
                            when {
                                taskToExecute != null -> {
                                    notifier.updateNotification(goalId, goal.title, taskToExecute.title)
                                    taskExecutor.executeOneTask(apiKey, goal, taskToExecute, workerId, models)
                                }
                                goal.isReadyForVerification -> {
                                    notifier.updateNotification(goalId, goal.title, "Verifying")
                                    verifier.verifyAndFinish(apiKey, goal, models)
                                }
                                else -> repairBlockedWorkflow(goal)
                            }
                        }

                        val finalGoalSnapshot = findGoal(goalId)

                        if (outcome == WorkerOutcome.DONE || outcome == WorkerOutcome.CONTINUE) {
                            ProviderRequestLedger.waitForSettlement()
                        }

                        val workerResult = when {
                            finalGoalSnapshot?.status == AgentGoalStatus.WAITING_FOR_NETWORK -> {
                                goalDiagnostics.info("worker_pausing_for_network")
                                Result.retry()
                            }
                            outcome == WorkerOutcome.CONTINUE -> {
                                goalDiagnostics.info("worker_enqueuing_continuation")
                                enqueueContinuationIfActive(goalId)
                                Result.success()
                            }
                            outcome == WorkerOutcome.DONE -> {
                                goalDiagnostics.info("worker_mission_done")
                                Result.success()
                            }
                            outcome == WorkerOutcome.RETRY -> {
                                goalDiagnostics.info("worker_requesting_retry")
                                Result.retry()
                            }
                            outcome == WorkerOutcome.FAIL -> {
                                goalDiagnostics.warning("worker_terminal_failure")
                                Result.failure()
                            }
                            else -> Result.failure()
                        }

                        diagnostics.info(
                            event = "worker_result_returned",
                            component = "worker",
                            fields = mapOf(
                                "goal_id" to goalId,
                                "worker_id" to workerId,
                                "outcome" to outcome.name,
                                "result" to workerResult.toString(),
                                "duration_ms" to (System.currentTimeMillis() - workerStartedAt)
                            )
                        )

                        researchMonitor.record(
                            category = "mission",
                            event = "worker_finished",
                            correlationId = goalId,
                            targetSessionId = client.currentSessionId,
                            fields = mapOf(
                                "goal_id" to goalId,
                                "worker_id" to workerId,
                                "outcome" to outcome.name,
                                "worker_result" to workerResult.toString(),
                                "final_status" to (finalGoalSnapshot?.status?.name ?: "deleted"),
                                "next_owner" to when {
                                    outcome == WorkerOutcome.CONTINUE -> "continuation_worker"
                                    workerResult == Result.retry() -> {
                                        if (finalGoalSnapshot?.status == AgentGoalStatus.WAITING_FOR_NETWORK) "network_condition"
                                        else "work_manager_retry"
                                    }
                                    finalGoalSnapshot?.status == AgentGoalStatus.WAITING_FOR_CREDENTIAL -> "credential_listener"
                                    finalGoalSnapshot?.status?.isInactive() == true -> "terminal_state"
                                    else -> "unknown"
                                }
                            )
                        )

                        if (finalGoalSnapshot?.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                            return@coroutineScope Result.retry()
                        }

                        goalDiagnostics.info(
                            "agent_worker_finished",
                            mapOf(
                                "outcome" to outcome.name,
                                "duration_ms" to (System.currentTimeMillis() - workerStartedAt),
                            ),
                        )
                        finalizeMonitorForTerminalGoal(goalId)
                        workerResult
                    } finally {
                        heartbeatJob.cancel()
                        heartbeatJob.join()
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            cancelActiveCalls("worker_coroutine_cancelled")
            ProviderRequestLedger.waitForSettlement()
            throw cancellation
        } finally {
            store.releaseLeaseAtomic(cancellationGoalId, workerId)
            cancellationRegistration.close()
        }
    }

    private fun finalizeMonitorForTerminalGoal(goalId: String) {
        val finalGoal = findGoal(goalId) ?: return
        if (!finalGoal.status.isFinalTerminalStatus() && finalGoal.status !in setOf(AgentGoalStatus.FAILED, AgentGoalStatus.BLOCKED)) return
        if (!researchMonitor.isActive()) return
        researchMonitor.record(
            category = "mission",
            event = "terminal_boundary_reached",
            level = if (finalGoal.status in setOf(
                    AgentGoalStatus.COMPLETED,
                    AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
                    AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
                )
            ) "INFO" else "WARN",
            correlationId = goalId,
            targetSessionId = client.currentSessionId,
            fields = mapOf(
                "goal_id" to goalId,
                "terminal_status" to finalGoal.status.name,
                "final_goal_snapshot" to finalGoal.toString(),
                "automatic_final_report" to true,
            ),
        )
        runCatching { researchMonitor.createReport(stopAfterReport = true) }
            .onFailure { error ->
                diagnostics.error(
                    "research_monitor_terminal_report_failed",
                    error,
                    mapOf("goal_id" to goalId, "terminal_status" to finalGoal.status.name),
                )
            }
    }

    private fun reconcileInterruptedWork(goalId: String) {
        val goal = findGoal(goalId) ?: return
        val needsRecovery = (goal.status == AgentGoalStatus.VERIFYING) ||
            goal.tasks.any { it.status == AgentTaskStatus.RUNNING }
        val needsLegacyRouteRecovery = goal.restoreAutoRouteAfterLegacyResearchDowngrade() != goal
        val normalizedClaims = normalizeDurableClaims(goal.tasks, goal.claims)
        val normalizedClaimIds = normalizedClaims.mapTo(mutableSetOf()) { it.id }
        val needsClaimNormalization = normalizedClaims != goal.claims ||
            goal.evidenceLinks.any { it.claimId !in normalizedClaimIds }
        val researchRoles = goal.tasks
            .asSequence()
            .filter { it.capability in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH) }
            .map(::researchPassRole)
            .toSet()
        val expectedSynthesisDescription = researchSynthesisEvidenceDescription(researchRoles)
        val needsSynthesisNormalization = expectedSynthesisDescription != null && goal.tasks.any { task ->
            task.capability == AgentCapability.SYNTHESIZE && task.acceptanceCriteria.any { criterion ->
                criterion.id.endsWith("_source_synthesis") && criterion.description != expectedSynthesisDescription
            }
        }
        if (
            !needsRecovery &&
            !needsLegacyRouteRecovery &&
            !needsSynthesisNormalization &&
            !needsClaimNormalization
        ) return
        store.updateGoal(goalId) { current ->
            val recovered = if (needsRecovery) AgentLifecycleReducer.recoverInterruptedWork(current) else current
            val rerouted = recovered.restoreAutoRouteAfterLegacyResearchDowngrade()
            val routeRecovered = if (rerouted.executionModelId != recovered.executionModelId) {
                rerouted.copy(
                    events = appendEvent(
                        rerouted.events,
                        "Restored this saved mission from the legacy free-router downgrade to OpenRouter Auto so provider web search and fetch remain available.",
                    ),
                )
            } else {
                rerouted
            }
            val cleanedClaims = normalizeDurableClaims(routeRecovered.tasks, routeRecovered.claims)
            val retainedClaimIds = cleanedClaims.mapTo(mutableSetOf()) { it.id }
            val cleaned = if (
                cleanedClaims != routeRecovered.claims ||
                routeRecovered.evidenceLinks.any { it.claimId !in retainedClaimIds }
            ) {
                routeRecovered.copy(
                    claims = cleanedClaims,
                    evidenceLinks = routeRecovered.evidenceLinks.filter { it.claimId in retainedClaimIds },
                    events = appendEvent(
                        routeRecovered.events,
                        "Removed planning questions and control criteria from the factual evidence graph and normalized unsupported confidence.",
                    ),
                )
            } else {
                routeRecovered
            }
            if (!needsSynthesisNormalization) {
                cleaned
            } else {
                cleaned.copy(
                    tasks = routeRecovered.tasks.map { task ->
                        if (task.capability != AgentCapability.SYNTHESIZE) {
                            task
                        } else {
                            task.copy(
                                acceptanceCriteria = task.acceptanceCriteria.map { criterion ->
                                    if (criterion.id.endsWith("_source_synthesis")) {
                                        criterion.copy(description = expectedSynthesisDescription)
                                    } else {
                                        criterion
                                    }
                                },
                            )
                        }
                    },
                    events = appendEvent(
                        cleaned.events,
                        "Normalized the synthesis gate to the research roles present in this saved plan.",
                    ),
                )
            }
        }
    }

    private fun enqueueContinuationIfActive(goalId: String) {
        val latest = findGoal(goalId) ?: return
        if (latest.status in setOf(AgentGoalStatus.PLANNING, AgentGoalStatus.QUEUED, AgentGoalStatus.RUNNING, AgentGoalStatus.VERIFYING)) {
            val generation = latest.executionLease?.generation ?: 0
            scheduler.enqueueContinuation(goalId, generation)
        }
    }

    private fun repairBlockedWorkflow(goal: AgentGoal): WorkerOutcome {
        val snapshot = store.updateGoal(goal.id) { current ->
            if (current.status.isInactive()) return@updateGoal current
            val ordered = current.tasks.sortedBy { it.order }
            if (ordered.isEmpty()) {
                val recoveryId = "recover_original_goal"
                return@updateGoal current.copy(
                    status = AgentGoalStatus.QUEUED,
                    tasks = listOf(
                        AgentTask(
                            id = recoveryId,
                            order = 0,
                            title = "Complete the original request",
                            instructions = "Reconstruct the missing plan from the original request, produce concrete progress, preserve evidence, and drive the mission toward verified completion.",
                            capability = AgentCapability.CORRECT,
                            status = AgentTaskStatus.QUEUED,
                            weight = 2.0,
                            acceptanceCriteria = listOf(
                                AgentAcceptanceCriterion(
                                    id = "${recoveryId}_progress",
                                    description = "The original request has a concrete, verifiable work product and a valid path to final verification.",
                                ),
                            ),
                        ),
                    ),
                    events = appendEvent(current.events, "The stored plan was empty. A deterministic recovery milestone was created from the original request."),
                    error = null,
                )
            }

            val earlierIds = mutableSetOf<String>()
            var dependencyRepairCount = 0
            val repaired = ordered.mapIndexed { index, task ->
                val validDependencies = task.dependsOn
                    .filter { dependency -> dependency != task.id && dependency in earlierIds }
                    .distinct()
                if (validDependencies != task.dependsOn) dependencyRepairCount += 1
                earlierIds += task.id
                if (task.status == AgentTaskStatus.COMPLETED) {
                    task.copy(order = index, dependsOn = validDependencies)
                } else {
                    val isExhausted = task.lifetimeAttemptCount >= 10 || (task.status == AgentTaskStatus.FAILED && task.consecutiveNoProgressCount >= 5)
                    if (isExhausted && task.status != AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE) {
                        task.copy(
                            order = index,
                            dependsOn = validDependencies,
                            status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
                            branchExhaustionReason = "Exhausted all recovery attempts in the task scheduler.",
                            branchExhaustedAt = System.currentTimeMillis()
                        )
                    } else {
                        task.copy(
                            order = index,
                            dependsOn = validDependencies,
                            status = if (task.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE) AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE else AgentTaskStatus.QUEUED,
                            lastError = if (validDependencies != task.dependsOn) {
                                "Invalid, cyclic, missing, or forward dependencies were removed automatically."
                            } else task.lastError,
                            finishedAt = if (task.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE) task.finishedAt else null,
                        )
                    }
                }
            }
            current.copy(
                status = AgentGoalStatus.QUEUED,
                tasks = repaired,
                events = appendEvent(
                    current.events,
                    if (dependencyRepairCount > 0) {
                        "Repaired $dependencyRepairCount blocked dependency set(s) and re-queued unfinished milestones without discarding completed work."
                    } else {
                        "No runnable milestone remained. Unfinished milestones were safely re-queued from the durable checkpoint."
                    },
                ),
                error = null,
            )
        }
        return if (snapshot.goals.firstOrNull { it.id == goal.id }?.status == AgentGoalStatus.QUEUED) {
            WorkerOutcome.CONTINUE
        } else {
            WorkerOutcome.DONE
        }
    }

    private fun waitForCredential(goalId: String, message: String) {
        store.updateGoal(goalId) { current ->
            if (current.status.isFinalTerminalStatus() || current.status == AgentGoalStatus.PAUSED) {
                current
            } else {
                val recovered = AgentLifecycleReducer.recoverInterruptedWork(current)
                recovered.copy(
                    status = AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                    error = message,
                    events = appendEvent(recovered.events, message),
                )
            }
        }
        diagnostics.warning("agent_waiting_for_credential", mapOf("goal_id" to goalId))
    }

    private fun findGoal(goalId: String): AgentGoal? = store.loadSnapshot().goals.firstOrNull { it.id == goalId }

    private fun reconcileMissingGoal(goalId: String) {
        val snapshot = store.loadSnapshot()
        val quarantined = snapshot.quarantinedMissions.firstOrNull { it.fileName.contains(goalId) }
        val reason = when {
            quarantined != null -> "Goal is quarantined: ${quarantined.reason}"
            else -> "Goal record not found in storage. Possibly deleted or migration failed."
        }
        
        researchMonitor.record(
            category = "scheduler",
            event = "orphan_work_reconciled",
            level = "WARN",
            correlationId = goalId,
            fields = mapOf(
                "goal_id" to goalId,
                "reason" to reason,
                "action" to "CANCEL_WORK",
            )
        )
        
        // Cancel all generations for this goal ID using tag
        scheduler.cancelAllForGoal(goalId)
    }

    companion object {
        const val KEY_GOAL_ID = "goal_id"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private var heartbeatCount = 0
    }
}
