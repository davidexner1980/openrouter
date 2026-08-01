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
            if (goal.status.isTerminal()) return@forEach

            val lease = goal.executionLease
            val isStale = AgentLeasePolicy.isStale(lease, now)
            val shouldRecover = when {
                goal.status == AgentGoalStatus.WAITING_FOR_NETWORK -> {
                    goal.nextRetryAt != null && now >= goal.nextRetryAt
                }
                lease == null -> true
                isStale -> true
                else -> false
            }

            if (shouldRecover) {
                diagnostics.info("mission_recovery_triggered", mapOf(
                    "goal_id" to goal.id,
                    "status" to goal.status.name,
                    "lease_stale" to isStale
                ))
                store.updateGoal(goal.id) { current ->
                    AgentLifecycleReducer.resume(
                        current,
                        reason = if (goal.status == AgentGoalStatus.WAITING_FOR_NETWORK) ResumeReason.NETWORK_RESTORED else ResumeReason.STALE_LEASE_RECOVERY,
                        message = if (goal.status == AgentGoalStatus.WAITING_FOR_NETWORK) "Automatically resumed mission after network wait." else "Recovered a stale or stranded mission lease."
                    )
                }
                scheduler.enqueue(goal.id, replace = false)
                recoveredCount++
            }
        }

        return Result.success()
    }

    private fun AgentGoalStatus.isTerminal(): Boolean = this in setOf(
        AgentGoalStatus.COMPLETED,
        AgentGoalStatus.CANCELLED,
        AgentGoalStatus.CANCELLING,
        AgentGoalStatus.REJECTED,
        AgentGoalStatus.FAILED // Legacy
    )
}
