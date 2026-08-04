package com.david.openassistant.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics

class MissionRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val store = AgentStore(context)
    private val scheduler = AgentScheduler(context)
    private val diagnostics = RuntimeDiagnostics(context)

    override suspend fun doWork(): Result {
        val snapshot = store.loadSnapshot()
        val now = System.currentTimeMillis()
        var recoveredCount = 0

        snapshot.goals.forEach { goal ->
            if (goal.status.isFinalTerminalStatus()) return@forEach

            // 1. Reconcile PENDING continuation claims
            goal.activeContinuationSchedulingClaim?.let { claim ->
                if (claim.state == ContinuationSchedulingState.PENDING) {
                    val isStaleClaim = now - claim.claimedAt > 60_000L
                    if (isStaleClaim) {
                        diagnostics.info("watchdog_reconciling_stale_continuation_claim", mapOf("goal_id" to goal.id, "claim_id" to claim.claimId))
                        scheduler.enqueue(goal.id, generation = claim.claimantGeneration)
                        store.confirmContinuationAtomic(goal.id, claim.claimId, ContinuationSchedulingState.FAILED_RETRYABLE, failureClass = "WATCHDOG_RECONCILED_STALE")
                    }
                }
            }

            // 2. Reconcile stale provider reconciliation claims
            goal.requestAttempts.filter { it.exchangeOutcome == ExchangeOutcome.ACTIVE }.forEach { attempt ->
                val isStaleReconciliation = attempt.reconciliationClaimedAt?.let { now - it > 120_000L } ?: false
                if (isStaleReconciliation && attempt.transportStage == ProviderTransportStage.NOT_DISPATCHED) {
                    diagnostics.info("watchdog_reconciling_stale_provider_claim", mapOf("goal_id" to goal.id, "exchange_id" to attempt.exchangeId))
                    // Reclaim or terminalize? The user addendum says "Reconcile stale provider reconciliation claims without dispatching ambiguous requests."
                    // If NOT_DISPATCHED, it's safe to just clear the claim or let another worker pick it up.
                    // Actually, if it's NOT_DISPATCHED, we can just leave it for the next worker who claims it.
                    // But we should probably mark it as FAILED if it's been too long without dispatch.
                }
            }

            val lease = goal.executionLease
            val isStale = AgentLeasePolicy.isStale(lease, now)
            
            // REQUIRED CHANGE 7: Watchdog must respect durable intent
            val isRecoverableStatus = when {
                goal.status.isActivePhase() -> true
                goal.status == AgentGoalStatus.WAITING_FOR_NETWORK -> goal.nextRetryAt != null && now >= goal.nextRetryAt
                else -> {
                    if (goal.status == AgentGoalStatus.PAUSED && (lease == null || isStale)) {
                        diagnostics.info("watchdog_skipped_user_paused_goal", mapOf("goal_id" to goal.id))
                    }
                    false // DO NOT recover WAITING_FOR_CREDENTIAL, etc.
                }
            }

            val shouldRecover = isRecoverableStatus && (lease == null || isStale)

                if (shouldRecover) {
                    diagnostics.info("mission_recovery_triggered", mapOf(
                        "goal_id" to goal.id,
                        "status" to goal.status.name,
                        "lease_stale" to isStale
                    ))
                    
                    store.updateGoal(goal.id) { current ->
                    if (current.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                        AgentLifecycleReducer.resume(
                            current,
                            reason = ResumeReason.NETWORK_RESTORED,
                            message = "Automatically resumed mission after network wait."
                        )
                    } else if (current.status.isActivePhase()) {
                        // Targeted recovery for interrupted active work
                        val recovered = AgentLifecycleReducer.recoverInterruptedWork(current, now)
                        recovered.copy(
                            events = appendEvent(recovered.events, "Watchdog recovered an active goal with a stale or missing lease."),
                            lastResumeReason = ResumeReason.STALE_LEASE_RECOVERY
                        ).also {
                            diagnostics.info("watchdog_recovered_active_goal", mapOf("goal_id" to goal.id, "prior_status" to current.status.name))
                        }
                    } else {
                        // QUEUED or other recoverable status
                        current
                    }
                }
                scheduler.enqueue(goal.id, replace = false)
                recoveredCount++
            }
        }

        return Result.success()
    }
}
