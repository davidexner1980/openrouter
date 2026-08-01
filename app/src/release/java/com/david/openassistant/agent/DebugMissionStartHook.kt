package com.david.openassistant.agent

import android.content.Context

/**
 * Release-variant no-op implementation.
 */
class DebugMissionStartHook(context: Context) : MissionStartBoundaryHook {
    override suspend fun onBoundaryReached(boundary: String, draft: ResearchDraft) {}

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {}
    }
}
