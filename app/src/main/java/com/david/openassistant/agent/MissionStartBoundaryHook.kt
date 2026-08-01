package com.david.openassistant.agent

/**
 * Narrow interface for debug-only boundary pauses during brief-to-mission start.
 * Injected into AgentInteractor to avoid passing Android Context through domain classes.
 */
interface MissionStartBoundaryHook {
    suspend fun onBoundaryReached(boundary: String, draft: ResearchDraft)
}

object NoOpMissionStartBoundaryHook : MissionStartBoundaryHook {
    override suspend fun onBoundaryReached(boundary: String, draft: ResearchDraft) {}
}
