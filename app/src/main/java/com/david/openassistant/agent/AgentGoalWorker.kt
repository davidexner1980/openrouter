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

        var activeTicket: AgentOwnershipTicket? = null
        var lastOutcome: WorkerOutcome? = null
        var resumedGoalForContinuation: AgentGoal? = null
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


                    val now = System.currentTimeMillis()
                    var goalSnapshot = findGoal(goalId) ?: return@coroutineScope Result.failure()
                    
                    if (goalSnapshot.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                        if (goalSnapshot.nextRetryAt == null || now >= goalSnapshot.nextRetryAt) {
                            store.updateGoalAtomic(goalId, null) { current ->
                                if (current.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                                    current.copy(
                                        status = current.resumeStatusAfterNetwork ?: AgentGoalStatus.QUEUED,
                                        networkRetryCount = current.networkRetryCount + 1,
                                        events = appendEvent(current.events, "Automatically resumed mission after network wait.")
                                    )
                                } else current
                            }.goals.firstOrNull { it.id == goalId } ?: return@coroutineScope Result.failure()
                            
                            goalSnapshot = findGoal(goalId) ?: return@coroutineScope Result.failure()
                        } else {
                            goalDiagnostics.info(
                                "agent_worker_waiting_for_network_retry",
                                mapOf("next_retry" to goalSnapshot.nextRetryAt)
                            )
                            return@coroutineScope Result.retry()
                        }
                    }
                    resumedGoalForContinuation = goalSnapshot

                    if (goalSnapshot.status.isInactive()) {
                        goalDiagnostics.info(
                            "agent_worker_skipped_inactive_goal",
                            mapOf("status" to goalSnapshot.status.name),
                        )
                        return@coroutineScope Result.success()
                    }

                    // CANONICAL STRUCTURAL REPAIR
                    val repairResult = store.repairUniversalToolAvailabilityStateAtomic(goalId)
                    if (repairResult is TypedRepairResult.Repaired) {
                        goalDiagnostics.info("universal_tool_availability_repaired")
                        goalSnapshot = findGoal(goalId) ?: return@coroutineScope Result.failure()
                    }

                    if (store.repairRecoveryStarvationAtomic(goalId)) {
                        goalDiagnostics.info("recovery_starvation_repaired")
                        goalSnapshot = findGoal(goalId) ?: return@coroutineScope Result.failure()
                    }

                    // RECOVERY PRIORITY: Check for active nonterminal recovery plan before ordinary task allocation
                    var hasActiveRecovery = checkActiveRecovery(goalSnapshot)

                    var allocationProfile = AgentResearchAllocator.profileForGoal(goalSnapshot, autonomyPolicy)
                    var gaps = AgentResearchAllocator.evaluateGaps(goalSnapshot, allocationProfile)
                    
                    diagnostics.info(
                        event = "runnable_task_selection_started",
                        component = "worker",
                        fields = mapOf("goal_id" to goalId, "recovery_priority" to hasActiveRecovery)
                    )
                    var taskSelection = if (hasActiveRecovery) {
                        AllocatedTaskSelection(null, "Active recovery priority.")
                    } else {
                        AgentResearchAllocator.chooseNextTask(goalSnapshot, allocationProfile, now)
                    }
                    
                    // REQUIRED CHANGE 5: No-runnable-task decision path
                    if (taskSelection.taskId == null) {
                        val decision = classifyNoRunnableTask(goalSnapshot, taskSelection)
                        diagnostics.info(
                            event = "no_runnable_task_classified",
                            component = "worker",
                            fields = mapOf(
                                "goal_id" to goalId,
                                "decision" to decision.name,
                                "status" to goalSnapshot.status.name,
                                "task_count" to goalSnapshot.tasks.size
                            )
                        )
                        
                        when (decision) {
                            NoTaskDecision.RETRY_LATER -> return@coroutineScope Result.retry()
                            NoTaskDecision.WAIT_FOR_NETWORK -> {
                                store.updateGoalAtomic(goalId, null) { current ->
                                    if (current.status != AgentGoalStatus.WAITING_FOR_NETWORK) {
                                        current.copy(status = AgentGoalStatus.WAITING_FOR_NETWORK, networkWaitReason = "All tasks waiting for network.")
                                    } else current
                                }
                                return@coroutineScope Result.success()
                            }
                            NoTaskDecision.WAIT_FOR_CREDENTIAL -> {
                                waitForCredential(goalId, null, "Waiting for a valid OpenRouter credential.")
                                return@coroutineScope Result.success()
                            }
                            NoTaskDecision.REPAIR_ONCE -> {
                                diagnostics.info(event = "stranded_recovery_goal_repaired", component = "worker", fields = mapOf("goal_id" to goalId))
                                val repairOutcome = repairBlockedWorkflow(goalSnapshot, null)
                                if (repairOutcome == WorkerOutcome.CONTINUE) {
                                    goalSnapshot = findGoal(goalId) ?: return@coroutineScope Result.failure()
                                    hasActiveRecovery = checkActiveRecovery(goalSnapshot)
                                    allocationProfile = AgentResearchAllocator.profileForGoal(goalSnapshot, autonomyPolicy)
                                    gaps = AgentResearchAllocator.evaluateGaps(goalSnapshot, allocationProfile)
                                    taskSelection = if (hasActiveRecovery) {
                                        AllocatedTaskSelection(null, "Active recovery priority.")
                                    } else {
                                        AgentResearchAllocator.chooseNextTask(goalSnapshot, allocationProfile, now)
                                    }
                                } else {
                                    return@coroutineScope Result.success()
                                }
                            }
                            NoTaskDecision.ACTION_REQUIRED -> {
                                diagnostics.warning(event = "stranded_goal_requires_action", component = "worker", fields = mapOf("goal_id" to goalId))
                                store.updateGoalAtomic(goalId, null) { current ->
                                    current.copy(status = AgentGoalStatus.BLOCKED_NEEDS_ACTION, error = "Mission stranded and requires manual intervention.")
                                }
                                return@coroutineScope Result.success()
                            }
                            NoTaskDecision.VERIFICATION -> {
                                // Continue to acquire planning lease for verification
                            }
                            NoTaskDecision.RECOVERY -> {
                                if (hasActiveRecovery) {
                                    // Proceed to acquire planning lease
                                } else {
                                    // REQUIRED CHANGE 5: Durable no-runnable recovery handoff
                                    diagnostics.info(event = "queued_goal_recovery_prepared", component = "worker", fields = mapOf("goal_id" to goalId))
                                    val repairOutcome = repairBlockedWorkflow(goalSnapshot, null)
                                    // V43: Re-load snapshot after mutation to avoid stale decision path
                                    goalSnapshot = findGoal(goalId) ?: return@coroutineScope Result.failure()
                                    
                                    hasActiveRecovery = checkActiveRecovery(goalSnapshot)
                                    allocationProfile = AgentResearchAllocator.profileForGoal(goalSnapshot, autonomyPolicy)
                                    gaps = AgentResearchAllocator.evaluateGaps(goalSnapshot, allocationProfile)
                                    if (!hasActiveRecovery) {
                                        taskSelection = AgentResearchAllocator.chooseNextTask(goalSnapshot, allocationProfile, now)
                                    } else {
                                        taskSelection = AllocatedTaskSelection(null, "Active recovery priority.")
                                    }
                                }
                            }
                            NoTaskDecision.TERMINAL_EXHAUSTED -> {
                                store.updateGoalAtomic(goalId, null) { current ->
                                    current.copy(status = AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED)
                                }
                                return@coroutineScope Result.success()
                            }
                            NoTaskDecision.FINALIZE -> {
                                // Continue to acquire planning lease for finalization
                            }
                        }
                    }

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

                    if (taskSelection.taskId != null) {
                        researchMonitor.record(
                            category = "allocation",
                            event = "research_allocation_next_task_selected",
                            correlationId = goalId,
                            fields = mapOf(
                                "goal_id" to goalId,
                                "task_id" to taskSelection.taskId,
                                "reason" to taskSelection.reason
                            )
                        )
                    }

                    diagnostics.info(
                        event = "lease_acquisition_started",
                        component = "lease",
                        fields = mapOf("goal_id" to goalId, "task_id" to (taskSelection.taskId ?: "none"))
                    )
                    // REQUIRED CHANGE 4: Restricted PlanningTicket acquisition
                    val acquisition = when {
                        goalSnapshot.status == AgentGoalStatus.PLANNING -> {
                            store.acquirePlanningLeaseAtomic(goalId, workerId)
                        }
                        hasActiveRecovery -> {
                            // RECOVERY PRIORITY
                            store.acquirePlanningLeaseAtomic(goalId, workerId)
                        }
                        taskSelection.taskId != null -> {
                            store.acquireTaskLeaseAtomic(goalId, workerId, taskSelection.taskId)
                        }
                        goalSnapshot.isReadyForVerification || 
                        goalSnapshot.status == AgentGoalStatus.FINALIZING -> {
                            store.acquirePlanningLeaseAtomic(goalId, workerId)
                        }
                        else -> {
                            diagnostics.warning(
                                event = "planning_lease_rejected_without_planning_operation",
                                component = "lease",
                                fields = mapOf("goal_id" to goalId, "status" to goalSnapshot.status.name)
                            )
                            return@coroutineScope Result.success()
                        }
                    }
                    
                    val (ticket, _) = when (acquisition) {
                        is LeaseAcquisitionResult.Acquired -> {
                            diagnostics.info(
                                event = "execution_ticket_created",
                                component = "lease",
                                fields = mapOf("goal_id" to goalId, "task_id" to (acquisition.ticket.taskId ?: "none"), "gen" to acquisition.ticket.generation)
                            )
                            acquisition.ticket to acquisition.goal
                        }
                        is LeaseAcquisitionResult.OrphanReclaimed -> {
                            diagnostics.info(
                                event = "execution_ticket_created",
                                component = "lease",
                                fields = mapOf("goal_id" to goalId, "task_id" to (acquisition.ticket.taskId ?: "none"), "gen" to acquisition.ticket.generation, "reclaimed" to true)
                            )
                            acquisition.ticket to acquisition.goal
                        }
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
                        is LeaseAcquisitionResult.Rejected -> {
                            goalDiagnostics.warning("agent_worker_lease_rejected", mapOf("reason" to acquisition.reason))
                            return@coroutineScope Result.failure()
                        }
                        is LeaseAcquisitionResult.StorageFailure -> {
                            goalDiagnostics.error("agent_worker_lease_storage_failure", acquisition.cause)
                            return@coroutineScope Result.retry()
                        }
                    }
                    activeTicket = ticket

                    // Canonical controlled stale-exchange reconciliation under goal lease lock
                    ProviderRequestLedger.reconcileStaleExchanges(store, goalId, workerId)
                    
                    // Protocol 42.6: Reload snapshot after potential reconciliation mutations to ensure
                    // subsequent priority and allocation checks use the ground truth.
                    val leasedGoal = findGoal(goalId) ?: return@coroutineScope Result.failure()
                    
                    // RECOVERY OWNERSHIP TRUTH: Validate plan after lease acquisition
                    if (hasActiveRecovery) {
                        if (ticket !is PlanningTicket) {
                            goalDiagnostics.warning("task_lease_acquired_during_active_recovery_priority_violation")
                            return@coroutineScope Result.retry()
                        }
                        
                        val latestPlanId = leasedGoal.activeRecoveryPlanId
                        val latestPlan = if (latestPlanId != null) leasedGoal.recoveryPlans.firstOrNull { it.id == latestPlanId } else null
                        
                        if (latestPlan == null || latestPlan.status.isTerminal()) {
                            goalDiagnostics.warning("recovery_plan_missing_or_terminal_after_lease")
                        } else if (goalSnapshot.activeRecoveryPlanId != null && latestPlan.id != goalSnapshot.activeRecoveryPlanId) {
                            goalDiagnostics.warning("recovery_plan_mismatch_after_lease")
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
                                "allocation_reason" to taskSelection.reason,
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
                            waitForCredential(goalId, ticket, "Waiting for a valid OpenRouter credential. All mission state is preserved.")
                            return@coroutineScope Result.success()
                        }

                        val models = runCatching { client.fetchModels(apiKey) }.getOrDefault(emptyList())

                        val hasActiveRecoveryAfterLease = checkActiveRecovery(leasedGoal)
                        val outcome = when {
                            leasedGoal.status == AgentGoalStatus.PLANNING && ticket is PlanningTicket -> {
                                notifier.updateNotification(goalId, leasedGoal.title, "Planning")
                                planner.plan(apiKey, leasedGoal, ticket, models)
                            }
                            (leasedGoal.status == AgentGoalStatus.RECOVERING || hasActiveRecoveryAfterLease) && ticket is PlanningTicket -> {
                                notifier.updateNotification(goalId, leasedGoal.title, "Recovering Research")
                                driveRecoveryProtocol(apiKey, leasedGoal, ticket)
                            }
                            else -> {
                                reconcileInterruptedWork(goalId, ticket)
                                if (leasedGoal.status.isInactive()) return@coroutineScope Result.success()
                                
                                val taskToExecute = AgentResearchAllocator.chooseNextTask(leasedGoal, allocationProfile, now).taskId?.let { id -> leasedGoal.tasks.firstOrNull { it.id == id } }
                                when {
                                    taskToExecute != null && ticket is TaskExecutionTicket -> {
                                        notifier.updateNotification(goalId, leasedGoal.title, taskToExecute.title)
                                        taskExecutor.executeOneTask(apiKey, leasedGoal, taskToExecute, ticket, models)
                                    }
                                    leasedGoal.isReadyForVerification && ticket is PlanningTicket -> {
                                        notifier.updateNotification(goalId, leasedGoal.title, "Verifying")
                                        verifier.verifyAndFinish(apiKey, leasedGoal, ticket, models)
                                    }
                                    else -> {
                                        repairBlockedWorkflow(leasedGoal, ticket)
                                    }
                                }
                            }
                        }
                        lastOutcome = outcome

                        val finalGoalSnapshot = findGoal(goalId)

                        if (outcome == WorkerOutcome.DONE || outcome == WorkerOutcome.CONTINUE) {
                            ProviderRequestLedger.waitForSettlement()
                        }

                        val workerResult = when {
                            finalGoalSnapshot?.status == AgentGoalStatus.WAITING_FOR_NETWORK -> {
                                goalDiagnostics.info("worker_pausing_for_network")
                                Result.retry()
                            }
                            outcome == WorkerOutcome.DONE -> {
                                val status = finalGoalSnapshot?.status ?: AgentGoalStatus.PAUSED
                                when {
                                    status.isFinalTerminalStatus() -> goalDiagnostics.info("worker_mission_terminal")
                                    status == AgentGoalStatus.WAITING_FOR_NETWORK || status == AgentGoalStatus.WAITING_FOR_CREDENTIAL -> goalDiagnostics.info("worker_wait_owner_confirmed")
                                    status == AgentGoalStatus.BLOCKED || status == AgentGoalStatus.BLOCKED_NEEDS_ACTION -> goalDiagnostics.info("worker_mission_blocked")
                                    else -> goalDiagnostics.info("worker_unit_completed")
                                }
                                Result.success()
                            }
                            outcome == WorkerOutcome.RETRY -> {
                                goalDiagnostics.info("worker_requesting_retry")
                                Result.retry()
                            }
                            outcome == WorkerOutcome.FAIL || outcome == WorkerOutcome.CONTINUE -> {
                                // Result.success() for CONTINUE because we'll enqueue after release
                                if (outcome == WorkerOutcome.CONTINUE) goalDiagnostics.info("worker_completed_unit_continuation_pending")
                                else goalDiagnostics.warning("worker_terminal_failure")
                                Result.success().takeIf { outcome == WorkerOutcome.CONTINUE } ?: Result.failure()
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
            val finalGoal = findGoal(cancellationGoalId)
            activeTicket?.let { store.releaseLeaseAtomic(it) }
            
            // REQUIRED CHANGE 6: CONTINUE MUST MEAN REAL FUTURE WORK
            if (lastOutcome == WorkerOutcome.CONTINUE) {
                val previousGoal = resumedGoalForContinuation
                if (finalGoal != null && previousGoal != null && ContinuationSchedulingPolicy.isSchedulable(finalGoal, previousGoal)) {
                    val currentFingerprint = ContinuationSchedulingPolicy.fingerprint(finalGoal)
                    enqueueContinuationIfActive(cancellationGoalId, currentFingerprint)
                } else {
                    diagnostics.info(
                        event = "no_op_continuation_suppressed",
                        component = "worker",
                        fields = mapOf(
                            "goal_id" to cancellationGoalId,
                            "initial_status" to (previousGoal?.status?.name ?: "none"),
                            "final_status" to (finalGoal?.status?.name ?: "none")
                        )
                    )
                }
            }
            cancellationRegistration.close()
        }
    }

    private enum class NoTaskDecision {
        RETRY_LATER,
        WAIT_FOR_NETWORK,
        WAIT_FOR_CREDENTIAL,
        REPAIR_ONCE,
        ACTION_REQUIRED,
        VERIFICATION,
        RECOVERY,
        TERMINAL_EXHAUSTED,
        FINALIZE
    }

    private fun classifyNoRunnableTask(goal: AgentGoal, selection: AllocatedTaskSelection): NoTaskDecision {
        if (selection.retryAfterCooldown) return NoTaskDecision.RETRY_LATER
        
        val activeRecoveryPlan = goal.activeRecoveryPlanId?.let { id -> goal.recoveryPlans.firstOrNull { it.id == id } }
        if (goal.status == AgentGoalStatus.RECOVERING || (activeRecoveryPlan != null && activeRecoveryPlan.status.isNonTerminal())) {
            return NoTaskDecision.RECOVERY
        }

        if (goal.status == AgentGoalStatus.PLANNING) return NoTaskDecision.RECOVERY // Should be planning
        if (goal.status == AgentGoalStatus.FINALIZING) return NoTaskDecision.FINALIZE
        if (goal.isReadyForVerification) return NoTaskDecision.VERIFICATION

        val completedIds = goal.tasks.filter { it.status == AgentTaskStatus.COMPLETED }.map { it.id }.toSet()
        val satisfiedTasks = goal.tasks.filter { 
            it.status != AgentTaskStatus.COMPLETED && 
            it.status != AgentTaskStatus.CANCELLED && 
            it.dependsOn.all(completedIds::contains) 
        }

        if (satisfiedTasks.isNotEmpty()) {
            if (satisfiedTasks.all { it.failureClass == "network_resolution" }) return NoTaskDecision.WAIT_FOR_NETWORK
            if (satisfiedTasks.all { it.branchExhaustionReason != null || it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }) return NoTaskDecision.RECOVERY
            return NoTaskDecision.REPAIR_ONCE
        }

        if (goal.tasks.isEmpty() && goal.status != AgentGoalStatus.PLANNING) return NoTaskDecision.REPAIR_ONCE
        
        if (goal.tasks.any { it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.CANCELLED && it.status != AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }) return NoTaskDecision.REPAIR_ONCE

        return NoTaskDecision.ACTION_REQUIRED
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

    private fun reconcileInterruptedWork(goalId: String, ticket: AgentOwnershipTicket) {
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
        store.updateGoalAtomic(goalId, ticket) { current ->
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

    private fun enqueueContinuationIfActive(goalId: String, fingerprint: String? = null) {
        val latest = findGoal(goalId) ?: return
        if (latest.status.isActivePhase()) {
            val generation = latest.executionLease?.generation ?: 0
            diagnostics.info(
                event = "continuation_enqueue_started",
                component = "scheduler",
                fields = mapOf("goal_id" to goalId, "gen" to generation, "fingerprint" to (fingerprint ?: "none"))
            )
            try {
                val result = scheduler.enqueueContinuation(goalId, generation, fingerprint)
                when (result) {
                    is SchedulingResult.NewlyEnqueued -> {
                        diagnostics.info(
                            event = "continuation_enqueued",
                            component = "scheduler",
                            fields = mapOf("goal_id" to goalId, "gen" to generation, "work_id" to result.workId.toString())
                        )
                    }
                    is SchedulingResult.ReusedActive -> {
                        diagnostics.info(
                            event = "continuation_reused",
                            component = "scheduler",
                            fields = mapOf("goal_id" to goalId, "gen" to generation)
                        )
                    }
                    is SchedulingResult.RejectedNoProgress -> {
                        diagnostics.info(
                            event = "continuation_suppressed",
                            component = "scheduler",
                            fields = mapOf("goal_id" to goalId, "reason" to "no_progress")
                        )
                    }
                    is SchedulingResult.CoalescedDuplicate -> {
                        diagnostics.info(
                            event = "continuation_suppressed",
                            component = "scheduler",
                            fields = mapOf("goal_id" to goalId, "reason" to "duplicate")
                        )
                    }
                    is SchedulingResult.EnqueueFailed -> {
                        diagnostics.error(
                            event = "continuation_enqueue_failed",
                            component = "scheduler",
                            throwable = result.error,
                            fields = mapOf("goal_id" to goalId, "gen" to generation)
                        )
                    }
                    else -> {
                        diagnostics.info(
                            event = "continuation_result_other",
                            component = "scheduler",
                            fields = mapOf("goal_id" to goalId, "result" to result::class.java.simpleName)
                        )
                    }
                }
            } catch (e: Throwable) {
                diagnostics.error(
                    event = "continuation_enqueue_failed",
                    component = "scheduler",
                    throwable = e,
                    fields = mapOf(
                        "goal_id" to goalId,
                        "gen" to generation
                    )
                )
            }
        }
    }

    private suspend fun driveRecoveryProtocol(
        apiKey: String,
        goal: AgentGoal,
        ticket: PlanningTicket,
    ): WorkerOutcome {
        val activePlanId = goal.activeRecoveryPlanId ?: return repairBlockedWorkflow(goal, ticket)
        val plan = goal.recoveryPlans.firstOrNull { it.id == activePlanId } ?: return repairBlockedWorkflow(goal, ticket)
        
        return when (plan.status) {
            RecoveryPlanStatus.PREPARED,
            RecoveryPlanStatus.GENERATING -> {
                planner.generateRecoveryProposal(apiKey, goal, plan, ticket)
            }
            RecoveryPlanStatus.FAILED_RETRYABLE -> {
                val logicalRequestId = plan.logicalProviderRequestId ?: "recovery-${plan.id}"
                val attempt = goal.requestAttempts.filter { it.logicalRequestId == logicalRequestId }.maxByOrNull { it.wireAttemptOrdinal }
                if (attempt != null && goal.retryAuthorizations.any { it.logicalRequestId == logicalRequestId && it.attemptOrdinal > attempt.wireAttemptOrdinal }) {
                    planner.generateRecoveryProposal(apiKey, goal, plan, ticket)
                } else {
                    store.repairTerminalRecoveryLivelockAtomic(goal.id, ticket)
                    WorkerOutcome.RETRY
                }
            }
            RecoveryPlanStatus.RECONCILIATION_REQUIRED,
            RecoveryPlanStatus.ALTERNATE_STRATEGY_REQUIRED -> {
                store.repairTerminalRecoveryLivelockAtomic(goal.id, ticket)
                WorkerOutcome.RETRY
            }
            RecoveryPlanStatus.READY_TO_COMMIT -> {
                planner.commitRecoveryEffect(goal, plan, ticket)
            }
            RecoveryPlanStatus.COMMITTED -> {
                diagnostics.info("committed_recovery_status_repaired", mapOf("goal_id" to goal.id, "plan_id" to plan.id))
                store.updateGoalAtomic(goal.id, ticket) { current ->
                    current.copy(status = AgentGoalStatus.QUEUED, activeRecoveryPlanId = null)
                }
                WorkerOutcome.CONTINUE
            }
            RecoveryPlanStatus.REJECTED_NOT_NOVEL,
            RecoveryPlanStatus.STRATEGY_EXHAUSTED -> {
                if (plan.selectedTactic == EscalationTactic.CYCLE_ADVANCE) {
                    store.updateGoalAtomic(goal.id, ticket) { current ->
                        current.copy(status = AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED, events = appendEvent(current.events, "Research cycles exhausted."))
                    }
                    WorkerOutcome.DONE
                } else {
                    repairBlockedWorkflow(goal, ticket)
                }
            }
            RecoveryPlanStatus.FAILED_NEEDS_ACTION -> {
                store.updateGoalAtomic(goal.id, ticket) { current ->
                    current.copy(status = AgentGoalStatus.BLOCKED_NEEDS_ACTION, error = "Recovery failed: ${plan.failureMessage}")
                }
                WorkerOutcome.DONE
            }
        }
    }

    private fun repairBlockedWorkflow(
        goal: AgentGoal,
        ticket: AgentOwnershipTicket?
    ): WorkerOutcome {
        val stalledTask = goal.tasks.firstOrNull { it.status == AgentTaskStatus.FAILED || it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }
            ?: goal.tasks.firstOrNull { it.status != AgentTaskStatus.COMPLETED }

        if (stalledTask != null) {
            val diagnosis = ResearchRecoveryEngine.diagnoseStall(goal, stalledTask, goal.freeOnly, false)
            if (diagnosis != ExecutionStallDiagnosis.NONE) {
                val tactic = ResearchRecoveryEngine.selectTactic(goal, stalledTask, diagnosis)
                if (tactic == EscalationTactic.MARK_EXHAUSTED || tactic == EscalationTactic.NONE) {
                    store.updateGoalAtomic(goal.id, ticket) { current ->
                        current.copy(
                            status = AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
                            events = appendEvent(current.events, "Research strategies exhausted for the current objective.")
                        )
                    }
                    return WorkerOutcome.DONE
                } else if (tactic == EscalationTactic.ASK_USER) {
                    store.updateGoalAtomic(goal.id, ticket) { current ->
                        current.copy(
                            status = AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
                            events = appendEvent(current.events, "Research stall detected. User clarification required.")
                        )
                    }
                    return WorkerOutcome.DONE
                } else {
                    val fingerprint = FingerprintUtils.calculateExecutionFingerprint(goal, stalledTask)
                    val planId = ResearchRecoveryEngine.generatePlanIdentity(goal.id, stalledTask.id, fingerprint, diagnosis, tactic)
                    
                    val existingPlan = goal.recoveryPlans.firstOrNull { it.id == planId }
                    if (existingPlan != null && existingPlan.status == RecoveryPlanStatus.COMMITTED) {
                        // Already tried this one and it didn't help? This shouldn't happen if fingerprint changed.
                    } else if (existingPlan == null || existingPlan.status == RecoveryPlanStatus.PREPARED) {
                        val newPlan = existingPlan ?: ResearchRecoveryPlan(
                            id = planId,
                            goalId = goal.id,
                            taskId = stalledTask.id,
                            inputExecutionFingerprint = fingerprint,
                            diagnosis = diagnosis,
                            selectedTactic = tactic,
                            status = RecoveryPlanStatus.PREPARED,
                            logicalProviderRequestId = null,
                            proposal = null,
                            proposalFingerprint = null,
                            validationResult = null,
                            failureClassification = null,
                            failureMessage = null
                        )
                        
                        val learningSummary = if (tactic == EscalationTactic.CYCLE_ADVANCE) constructLearningSummary(goal, "Exhausting all tactics.") else null

                        store.updateGoalAtomic(goal.id, ticket) { current ->
                            val updatedCycles = if (learningSummary != null) {
                                current.researchCycles.map { if (it.id == current.activeResearchCycleId) it.copy(learningSummary = learningSummary) else it }
                            } else current.researchCycles
                            
                            val isNewPlan = existingPlan == null
                            current.copy(
                                status = AgentGoalStatus.RECOVERING,
                                activeRecoveryPlanId = planId,
                                recoveryPlans = if (isNewPlan) current.recoveryPlans + newPlan else current.recoveryPlans,
                                researchCycles = updatedCycles,
                                events = if (isNewPlan) appendEvent(current.events, "Research stalled: ${diagnosis.name}. Prepared tactic pivot: ${tactic.name}.") else current.events
                            )
                        }
                        return WorkerOutcome.CONTINUE
                    } else if (existingPlan.status.isNonTerminal()) {
                        // V43: Align goal status with existing active recovery plan to avoid loop in QUEUED state
                        store.updateGoalAtomic(goal.id, ticket) { current ->
                            if (current.status != AgentGoalStatus.RECOVERING) {
                                current.copy(
                                    status = AgentGoalStatus.RECOVERING,
                                    activeRecoveryPlanId = planId,
                                    events = appendEvent(current.events, "Resumed existing recovery plan: ${tactic.name}.")
                                )
                            } else current
                        }
                        return WorkerOutcome.CONTINUE
                    }
                }
            }
        }

        val snapshot = store.updateGoalAtomic(goal.id, ticket) { current ->
            if (current.status.isInactive()) return@updateGoalAtomic current
            val ordered = current.tasks.sortedBy { it.order }
            if (ordered.isEmpty()) {
                val recoveryId = "recover_original_goal"
                return@updateGoalAtomic current.copy(
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

    private fun constructLearningSummary(goal: AgentGoal, reason: String): ResearchCycleLearningSummary {
        val establishedFindings = goal.claims
            .filter { it.support == AgentClaimSupport.SUPPORTED }
            .map { it.text }
            .take(20)
        
        // V42.4: Refined accepted evidence classification.
        // Discovery items (RESEARCH_HIT) and unverified research are not automatically accepted.
        // We only carry forward evidence that is explicitly cited by a supported or partial claim.
        val citedEvidenceIds = goal.claims
            .asSequence()
            .filter { it.support == AgentClaimSupport.SUPPORTED || it.support == AgentClaimSupport.PARTIAL }
            .flatMap { it.supportingEvidenceIds }
            .toSet()

        val acceptedEvidenceIds = goal.evidence
            .filter { it.id in citedEvidenceIds }
            .map { it.id }
            
        val acceptedClaimIds = goal.claims
            .filter { it.support == AgentClaimSupport.SUPPORTED || it.support == AgentClaimSupport.PARTIAL }
            .map { it.id }

        val remainingUnresolvedGaps = goal.tasks
            .filter { it.status != AgentTaskStatus.COMPLETED }
            .map { it.title }

        val contradictions = goal.claims
            .filter { it.support == AgentClaimSupport.CONTRADICTED }
            .map { it.text }

        val allRejectedQueries = goal.tasks.flatMap { it.rejectedQueries }
        val rejectedOrUnreliableMaterial = allRejectedQueries.map { it.originalQuery }

        val attemptedTactics = goal.recoveryPlans
            .filter { it.status == RecoveryPlanStatus.COMMITTED }
            .map { it.selectedTactic }

        return ResearchCycleLearningSummary(
            establishedFindings = establishedFindings,
            acceptedEvidenceIds = acceptedEvidenceIds,
            acceptedClaimIds = acceptedClaimIds,
            remainingUnresolvedGaps = remainingUnresolvedGaps,
            contradictions = contradictions,
            rejectedOrUnreliableMaterial = rejectedOrUnreliableMaterial,
            exhaustedQueryApproaches = allRejectedQueries.map { it.normalizedQuery }.distinct(),
            exhaustedSourceFamilies = emptyList(),
            attemptedTactics = attemptedTactics,
            failedStrategyFingerprints = emptyList(),
            carryForwardEvidenceIds = acceptedEvidenceIds,
            advancementReason = reason
        )
    }

    private fun waitForCredential(goalId: String, ticket: AgentOwnershipTicket?, message: String) {
        store.updateGoalAtomic(goalId, ticket) { current ->
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

    private fun checkActiveRecovery(goal: AgentGoal): Boolean {
        val activeRecoveryId = goal.activeRecoveryPlanId
        val activeRecoveryPlan = if (activeRecoveryId != null) goal.recoveryPlans.firstOrNull { it.id == activeRecoveryId } else null
        return goal.status == AgentGoalStatus.RECOVERING || (activeRecoveryPlan != null && activeRecoveryPlan.status.isNonTerminal())
    }

    companion object {
        const val KEY_GOAL_ID = "goal_id"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private var heartbeatCount = 0
    }
}
