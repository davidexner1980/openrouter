package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionUiStateTest {

    @Test
    fun `test actions for RUNNING mission`() {
        val goal = mockGoal(AgentGoalStatus.RUNNING, hasActiveLease = true)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.PAUSE))
        assertTrue(actions.contains(MissionUiAction.STOP))
        assertFalse(actions.contains(MissionUiAction.RESUME))
        assertFalse(actions.contains(MissionUiAction.DELETE))
    }

    @Test
    fun `test actions for PAUSED mission`() {
        val goal = mockGoal(AgentGoalStatus.PAUSED)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.RESUME))
        assertTrue(actions.contains(MissionUiAction.STOP))
        assertFalse(actions.contains(MissionUiAction.PAUSE))
        assertFalse(actions.contains(MissionUiAction.DELETE))
    }

    @Test
    fun `test actions for COMPLETED mission`() {
        val goal = mockGoal(AgentGoalStatus.COMPLETED)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.DELETE))
        assertFalse(actions.contains(MissionUiAction.PAUSE))
        assertFalse(actions.contains(MissionUiAction.RESUME))
        assertFalse(actions.contains(MissionUiAction.STOP))
    }

    @Test
    fun `test actions for verified terminal missions`() {
        val strong = MissionUiLogic.getAvailableActions(mockGoal(AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE))
        val qualified = MissionUiLogic.getAvailableActions(mockGoal(AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS))
        val partial = MissionUiLogic.getAvailableActions(mockGoal(AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE))

        listOf(strong, qualified, partial).forEach { actions ->
            assertTrue(actions.contains(MissionUiAction.DELETE))
            assertFalse(actions.contains(MissionUiAction.PAUSE))
            assertFalse(actions.contains(MissionUiAction.RESUME))
            assertFalse(actions.contains(MissionUiAction.STOP))
        }
    }

    @Test
    fun `test actions for QUEUED mission with no active worker`() {
        val goal = mockGoal(AgentGoalStatus.QUEUED)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.RESUME))
        assertTrue(actions.contains(MissionUiAction.STOP))
        assertFalse(actions.contains(MissionUiAction.PAUSE))
    }

    @Test
    fun `test actions for BLOCKED mission`() {
        val goal = mockGoal(AgentGoalStatus.BLOCKED)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.RESUME))
        assertTrue(actions.contains(MissionUiAction.STOP))
    }

    @Test
    fun `test actions for FAILED mission`() {
        val goal = mockGoal(AgentGoalStatus.FAILED)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.RESUME))
        assertTrue(actions.contains(MissionUiAction.DELETE))
        assertFalse(actions.contains(MissionUiAction.PAUSE))
    }

    @Test
    fun `test actions for clarification needed mission`() {
        val goal = mockGoal(AgentGoalStatus.REQUIRES_USER_CLARIFICATION)
        val actions = MissionUiLogic.getAvailableActions(goal)
        assertTrue(actions.contains(MissionUiAction.RESUME))
        assertTrue(actions.contains(MissionUiAction.STOP))
        assertFalse(actions.contains(MissionUiAction.PAUSE))
        assertFalse(actions.contains(MissionUiAction.DELETE))
    }

    private fun mockGoal(status: AgentGoalStatus, hasActiveLease: Boolean = false) = AgentGoal(
        id = "goal-1",
        conversationId = "conv-1",
        userRequest = "test",
        title = "test",
        objective = "test",
        finalOutputDescription = "test",
        status = status,
        plannerModelId = "model",
        executionModelId = "model",
        tasks = emptyList(),
        executionLease = if (hasActiveLease) AgentExecutionLease(
            workerId = "worker-1",
            ownerProcessSessionId = "session-1",
            taskId = "task-1",
            attemptId = "attempt-1",
            generation = 1,
            acquiredAt = System.currentTimeMillis(),
            heartbeatAt = System.currentTimeMillis()
        ) else null,
    )
}
