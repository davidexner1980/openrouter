package com.david.openassistant.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles mission control signals from notifications (e.g., Stop Mission).
 */
class MissionControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val goalId = intent.getStringExtra(EXTRA_GOAL_ID)
        
        if (action == ACTION_STOP_MISSION && goalId != null) {
            Log.i("MissionControl", "Stop requested for mission $goalId from notification.")
            
            // 1. Mark mission as CANCELLED in durable storage immediately.
            // This prevents the scheduler from resuming it if the worker is killed.
            val store = AgentStore(context)
            store.updateGoal(goalId) { current ->
                if (current.status.isFinalTerminalStatus()) {
                    current
                } else {
                    current.copy(
                        status = AgentGoalStatus.CANCELLED,
                        events = appendEvent(current.events, "Mission stopped via notification control.")
                    )
                }
            }

            // 2. Signal WorkManager to cancel the job.
            val scheduler = AgentScheduler(context)
            scheduler.cancelAllForGoal(goalId)
        }
    }

    companion object {
        const val ACTION_STOP_MISSION = "com.david.openassistant.ACTION_STOP_MISSION"
        const val EXTRA_GOAL_ID = "extra_goal_id"
    }
}
