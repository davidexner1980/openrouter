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
            
            val store = AgentStore(context)
            val scheduler = AgentScheduler(context)

            // 1. Mark mission as CANCELLING.
            store.updateGoal(goalId) { current ->
                if (current.status.isFinalTerminalStatus()) current 
                else current.copy(status = AgentGoalStatus.CANCELLING)
            }

            // 2. Signal WorkManager to cancel the job and active provider/tool calls.
            scheduler.cancelAllForGoal(goalId)
            
            // 3. Persist CANCELLED terminal state.
            store.updateGoal(goalId) { current ->
                if (current.status == AgentGoalStatus.CANCELLING) {
                    current.copy(
                        status = AgentGoalStatus.CANCELLED,
                        events = appendEvent(current.events, "Mission stopped and cancelled via notification control.")
                    )
                } else current
            }
        }
    }

    companion object {
        const val ACTION_STOP_MISSION = "com.david.openassistant.ACTION_STOP_MISSION"
        const val EXTRA_GOAL_ID = "extra_goal_id"
    }
}
