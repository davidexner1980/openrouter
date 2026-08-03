package com.david.openassistant.agent

import org.junit.Test
import org.junit.Assert.assertEquals

class AgentLifecycleReducerTest {

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
    fun testPauseIntermediateStates() {
        val intermediateStates = listOf(
            AgentGoalStatus.RESEARCHING,
            AgentGoalStatus.RETRIEVING,
            AgentGoalStatus.EXTRACTING,
            AgentGoalStatus.VALIDATING,
            AgentGoalStatus.SYNTHESIZING
        )
        
        for (state in intermediateStates) {
            val goal = createTestGoal(state)
            val paused = AgentLifecycleReducer.pause(goal)
            assertEquals("Should be able to pause from $state", AgentGoalStatus.PAUSED, paused.status)
        }
    }

    @Test
    fun testRecoverIntermediateStates() {
        val intermediateStates = listOf(
            AgentGoalStatus.RESEARCHING,
            AgentGoalStatus.RETRIEVING,
            AgentGoalStatus.EXTRACTING,
            AgentGoalStatus.VALIDATING,
            AgentGoalStatus.SYNTHESIZING
        )
        
        for (state in intermediateStates) {
            val goal = createTestGoal(state)
            val recovered = AgentLifecycleReducer.recoverInterruptedWork(goal)
            assertEquals("Should recover from $state to QUEUED", AgentGoalStatus.QUEUED, recovered.status)
        }
    }
}
