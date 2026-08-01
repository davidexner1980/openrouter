package com.david.openassistant.agent

import java.util.UUID

/**
 * Resolves a pending research brief against the durable goal store after process restart.
 *
 * STARTING, STARTED, and transient DurableSchedulingStates are transitional draft states.
 * Replaying automatic starts must preserve the STARTING status and linked Goal IDs so process
 * deaths never demote a brief into an editable, duplicate READY draft.
 */
object ResearchMissionStartRecovery {
    enum class Action {
        NONE,
        KEEP_DRAFT,
        CLEAR_DRAFT_FOR_EXISTING_GOAL,
        REPLAY_INTERRUPTED_START,
    }

    data class Decision(
        val action: Action,
        val draftForUi: ResearchDraft?,
        val existingGoalId: String? = null,
        val shouldReplayStart: Boolean = false,
        val recoveryReason: String? = null,
    )

    fun decide(
        rawDraft: ResearchDraft?,
        goals: List<AgentGoal>,
    ): Decision {
        if (rawDraft == null) {
            return Decision(Action.NONE, draftForUi = null)
        }

        // Backward compatibility migration for drafts without linkedGoalId
        val pendingDraft = if (rawDraft.linkedGoalId.isNullOrBlank() && (rawDraft.status == ResearchDraftStatus.STARTING || rawDraft.status == ResearchDraftStatus.STARTED)) {
            rawDraft.copy(linkedGoalId = UUID.randomUUID().toString())
        } else {
            rawDraft
        }

        val matchingGoal = goals.firstOrNull { goal ->
            goal.submissionId == pendingDraft.id ||
                (!pendingDraft.linkedGoalId.isNullOrBlank() && goal.id == pendingDraft.linkedGoalId)
        }

        if (matchingGoal != null) {
            return Decision(
                action = Action.CLEAR_DRAFT_FOR_EXISTING_GOAL,
                draftForUi = null,
                existingGoalId = matchingGoal.id,
            )
        }

        val isTransitionalState = pendingDraft.status == ResearchDraftStatus.STARTING ||
            pendingDraft.status == ResearchDraftStatus.STARTED ||
            pendingDraft.durableSchedulingState in setOf(
                DurableSchedulingState.GOAL_PERSISTED,
                DurableSchedulingState.SCHEDULING_PENDING,
                DurableSchedulingState.SCHEDULING_FAILED,
            )

        if (isTransitionalState) {
            val recoveryReason = when {
                pendingDraft.durableSchedulingState == DurableSchedulingState.SCHEDULING_FAILED -> "scheduling_failed_retry"
                pendingDraft.status == ResearchDraftStatus.STARTING -> "interrupted_starting"
                pendingDraft.status == ResearchDraftStatus.STARTED -> "inconsistent_started"
                else -> "interrupted_transitional_scheduling"
            }

            // Maintain status = STARTING and preserve IDs during replay to eliminate the second crash window
            return Decision(
                action = Action.REPLAY_INTERRUPTED_START,
                draftForUi = pendingDraft.copy(
                    status = ResearchDraftStatus.STARTING,
                    updatedAt = System.currentTimeMillis(),
                ),
                shouldReplayStart = true,
                recoveryReason = recoveryReason,
            )
        }

        return Decision(
            action = Action.KEEP_DRAFT,
            draftForUi = pendingDraft,
        )
    }
}
