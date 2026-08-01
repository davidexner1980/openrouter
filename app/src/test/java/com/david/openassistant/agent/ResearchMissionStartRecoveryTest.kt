package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchMissionStartRecoveryTest {
    @Test
    fun noPendingDraftRequiresNoRecovery() {
        val decision = ResearchMissionStartRecovery.decide(null, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.NONE, decision.action)
        assertNull(decision.draftForUi)
        assertFalse(decision.shouldReplayStart)
    }

    @Test
    fun editableDraftRemainsEditable() {
        val draft = testDraft(status = ResearchDraftStatus.READY)

        val decision = ResearchMissionStartRecovery.decide(draft, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.KEEP_DRAFT, decision.action)
        assertEquals(draft, decision.draftForUi)
        assertFalse(decision.shouldReplayStart)
    }

    @Test
    fun interruptedStartingDraftBecomesRetryableAndIsReplayed() {
        val draft = testDraft(
            status = ResearchDraftStatus.STARTING,
            linkedGoalId = "goal-expected",
        )

        val decision = ResearchMissionStartRecovery.decide(draft, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.REPLAY_INTERRUPTED_START, decision.action)
        assertEquals(ResearchDraftStatus.STARTING, decision.draftForUi?.status)
        assertEquals("goal-expected", decision.draftForUi?.linkedGoalId)
        assertTrue(decision.shouldReplayStart)
        assertEquals("interrupted_starting", decision.recoveryReason)
    }

    @Test
    fun interruptedStartedDraftWithoutGoalIsReplayed() {
        val draft = testDraft(
            status = ResearchDraftStatus.STARTED,
            linkedGoalId = "goal-missing-after-start",
        )

        val decision = ResearchMissionStartRecovery.decide(draft, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.REPLAY_INTERRUPTED_START, decision.action)
        assertEquals(ResearchDraftStatus.STARTING, decision.draftForUi?.status)
        assertEquals("goal-missing-after-start", decision.draftForUi?.linkedGoalId)
        assertTrue(decision.shouldReplayStart)
        assertEquals("inconsistent_started", decision.recoveryReason)
    }

    @Test
    fun existingGoalClearsTransitionalDraftBySubmissionId() {
        val draft = testDraft(status = ResearchDraftStatus.STARTING)
        val goal = testGoal(id = "goal-existing", submissionId = draft.id)

        val decision = ResearchMissionStartRecovery.decide(draft, listOf(goal))

        assertEquals(ResearchMissionStartRecovery.Action.CLEAR_DRAFT_FOR_EXISTING_GOAL, decision.action)
        assertEquals(goal.id, decision.existingGoalId)
        assertNull(decision.draftForUi)
        assertFalse(decision.shouldReplayStart)
    }

    @Test
    fun existingGoalClearsTransitionalDraftByLinkedGoalId() {
        val draft = testDraft(
            id = "draft-with-different-submission",
            status = ResearchDraftStatus.STARTED,
            linkedGoalId = "goal-linked",
        )
        val goal = testGoal(id = "goal-linked", submissionId = "older-submission-id")

        val decision = ResearchMissionStartRecovery.decide(draft, listOf(goal))

        assertEquals(ResearchMissionStartRecovery.Action.CLEAR_DRAFT_FOR_EXISTING_GOAL, decision.action)
        assertEquals(goal.id, decision.existingGoalId)
        assertNull(decision.draftForUi)
    }

    @Test
    fun legacyDraftWithoutLinkedGoalIdIsAssignedLinkedGoalIdAndReplayed() {
        val legacyDraft = testDraft(
            status = ResearchDraftStatus.STARTING,
            linkedGoalId = null,
        )

        val decision = ResearchMissionStartRecovery.decide(legacyDraft, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.REPLAY_INTERRUPTED_START, decision.action)
        assertNotNull(decision.draftForUi?.linkedGoalId)
        assertEquals(legacyDraft.id, decision.draftForUi?.id)
        assertTrue(decision.shouldReplayStart)
    }

    @Test
    fun schedulingFailedDraftReplaysWithSchedulingFailedReason() {
        val draft = testDraft(
            status = ResearchDraftStatus.STARTING,
            linkedGoalId = "goal-failed-scheduling",
            durableSchedulingState = DurableSchedulingState.SCHEDULING_FAILED,
        )

        val decision = ResearchMissionStartRecovery.decide(draft, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.REPLAY_INTERRUPTED_START, decision.action)
        assertEquals("scheduling_failed_retry", decision.recoveryReason)
        assertEquals(DurableSchedulingState.SCHEDULING_FAILED, decision.draftForUi?.durableSchedulingState)
    }

    private fun testDraft(
        id: String = "draft-1",
        status: ResearchDraftStatus,
        linkedGoalId: String? = null,
        durableSchedulingState: DurableSchedulingState = DurableSchedulingState.NOT_SCHEDULED,
    ) = ResearchDraft(
        id = id,
        conversationId = "conversation-1",
        title = "Durable research mission",
        question = "What must be verified?",
        objective = "Produce source-traceable evidence.",
        status = status,
        durableSchedulingState = durableSchedulingState,
        linkedGoalId = linkedGoalId,
    )

    private fun testGoal(
        id: String,
        submissionId: String,
    ) = AgentGoal(
        id = id,
        conversationId = "conversation-1",
        submissionId = submissionId,
        userRequest = "What must be verified?",
        title = "Durable research mission",
        objective = "Produce source-traceable evidence.",
        finalOutputDescription = "A verified report.",
        status = AgentGoalStatus.PLANNING,
        plannerModelId = "openrouter/auto-beta",
        executionModelId = "openrouter/auto-beta",
        tasks = emptyList(),
    )
}
