package com.david.openassistant.agent

import org.junit.Test
import org.junit.Assert.assertTrue

class MissionUiLogicTest {

    private fun createTestGoal(status: AgentGoalStatus): AgentGoal {
        return AgentGoal(
            id = "g1",
            conversationId = "c1",
            userRequest = "R",
            title = "T",
            objective = "O",
            finalOutputDescription = "F",
            status = status,
            plannerModelId = "m1",
            executionModelId = "m2",
            tasks = listOf(
                AgentTask(
                    id = "t1",
                    order = 0,
                    title = "Task 1",
                    instructions = "Do it",
                    capability = AgentCapability.WEB_RESEARCH,
                    status = AgentTaskStatus.RUNNING
                )
            )
        )
    }

    @Test
    fun testIntermediateStatesActive() {
        val intermediateStates = listOf(
            AgentGoalStatus.RESEARCHING,
            AgentGoalStatus.RETRIEVING,
            AgentGoalStatus.EXTRACTING,
            AgentGoalStatus.VALIDATING,
            AgentGoalStatus.SYNTHESIZING
        )
        
        for (state in intermediateStates) {
            val goal = createTestGoal(state)
            // Mocking active lease by giving it a lease with current timestamp
            val goalWithLease = goal.copy(
                executionLease = AgentExecutionLease(
                    workerId = "w1",
                    ownerProcessSessionId = "s1",
                    taskId = "t1",
                    attemptId = "a1",
                    generation = 1,
                    acquiredAt = System.currentTimeMillis(),
                    heartbeatAt = System.currentTimeMillis()
                )
            )
            val actions = MissionUiLogic.getAvailableActions(goalWithLease)
            assertTrue("State $state should allow PAUSE if lease is active", actions.contains(MissionUiAction.PAUSE))
            assertTrue("State $state should allow STOP", actions.contains(MissionUiAction.STOP))
        }
    }

    @Test
    fun testIntermediateStatesStranded() {
        val intermediateStates = listOf(
            AgentGoalStatus.RESEARCHING,
            AgentGoalStatus.RETRIEVING,
            AgentGoalStatus.EXTRACTING,
            AgentGoalStatus.VALIDATING,
            AgentGoalStatus.SYNTHESIZING
        )
        
        for (state in intermediateStates) {
            val goal = createTestGoal(state)
            // No lease -> stranded
            val actions = MissionUiLogic.getAvailableActions(goal)
            assertTrue("State $state should allow RESUME if stranded (no lease)", actions.contains(MissionUiAction.RESUME))
        }
    }
}
