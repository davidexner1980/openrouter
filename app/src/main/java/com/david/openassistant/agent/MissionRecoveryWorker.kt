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
                            status = if (recovered.status.isActivePhase()) AgentGoalStatus.QUEUED else recovered.status,
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
