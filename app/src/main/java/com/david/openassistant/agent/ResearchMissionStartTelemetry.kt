package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.ResearchMonitor

/**
 * Authoritative telemetry for the brief-to-mission lifecycle.
 *
 * Enforces field requirements and outcome-specific events defined in the
 * version 1.8.26 repair specification.
 */
object ResearchMissionStartTelemetry {

    fun briefCreated(
        monitor: ResearchMonitor,
        submissionId: String,
        draftId: String,
        linkedGoalId: String,
        conversationId: String,
        automaticStart: Boolean,
        routingProfile: String? = null
    ) {
        require(submissionId.isNotBlank()) { "submission_id must not be blank" }
        require(linkedGoalId.isNotBlank()) { "linked_goal_id must not be blank" }

        monitor.record(
            category = "mission",
            event = "research_brief_created",
            correlationId = submissionId,
            fields = mapOf(
                "submission_id" to submissionId,
                "draft_id" to draftId,
                "linked_goal_id" to linkedGoalId,
                "conversation_id" to conversationId,
                "automatic_start" to automaticStart,
                "routing_profile" to routingProfile
            )
        )
    }

    fun startRequested(
        monitor: ResearchMonitor,
        submissionId: String,
        draftId: String,
        linkedGoalId: String,
        automaticStart: Boolean,
        previousDraftState: String,
        targetDraftState: String,
        routingProfile: String,
        conversationId: String
    ) {
        require(submissionId.isNotBlank()) { "submission_id must not be blank" }
        require(linkedGoalId.isNotBlank()) { "linked_goal_id must not be blank" }

        monitor.record(
            category = "mission",
            event = "start_mission_requested",
            correlationId = submissionId,
            fields = mapOf(
                "submission_id" to submissionId,
                "draft_id" to draftId,
                "linked_goal_id" to linkedGoalId,
                "automatic_start" to automaticStart,
                "previous_draft_state" to previousDraftState,
                "target_draft_state" to targetDraftState,
                "routing_profile" to routingProfile,
                "conversation_id" to conversationId
            )
        )
    }

    fun startResolved(
        monitor: ResearchMonitor,
        submissionId: String,
        goalId: String,
        resolution: String // "created" | "reused" | "recovered" | "waiting_for_credential"
    ) {
        monitor.record(
            category = "mission",
            event = "mission_start_resolved",
            correlationId = goalId,
            fields = mapOf(
                "submission_id" to submissionId,
                "goal_id" to goalId,
                "resolution" to resolution
            )
        )
    }

    fun workerEnqueued(
        monitor: ResearchMonitor,
        goalId: String,
        submissionId: String?,
        policy: String,
        workId: String,
        workInfoState: String,
    ) {
        monitor.record(
            category = "mission",
            event = "mission_worker_enqueued",
            correlationId = goalId,
            fields = mapOf(
                "goal_id" to goalId,
                "submission_id" to submissionId,
                "policy" to policy,
                "work_id" to workId,
                "work_info_state" to workInfoState
            )
        )
    }

    fun workerReused(
        monitor: ResearchMonitor,
        goalId: String,
        submissionId: String?,
        policy: String,
        workId: String,
        workInfoState: String,
    ) {
        monitor.record(
            category = "mission",
            event = "mission_worker_reused",
            correlationId = goalId,
            fields = mapOf(
                "goal_id" to goalId,
                "submission_id" to submissionId,
                "policy" to policy,
                "work_id" to workId,
                "work_info_state" to workInfoState
            )
        )
    }

    fun workerEnqueueFailed(
        monitor: ResearchMonitor,
        goalId: String,
        submissionId: String?,
        exceptionType: String,
        message: String,
        policy: String,
        type: String // "initial", "recovery", "retry", "continuation"
    ) {
        monitor.record(
            category = "mission",
            event = "mission_worker_enqueue_failed",
            level = "ERROR",
            correlationId = goalId,
            fields = mapOf(
                "goal_id" to goalId,
                "submission_id" to submissionId,
                "exception_type" to exceptionType,
                "message" to message,
                "policy" to policy,
                "type" to type
            )
        )
    }
}
