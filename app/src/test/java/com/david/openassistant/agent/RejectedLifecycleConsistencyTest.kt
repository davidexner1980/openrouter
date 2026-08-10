package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class RejectedLifecycleConsistencyTest {

    @Test
    fun testRejectedIsFinalTerminal() {
        assertTrue(AgentGoalStatus.REJECTED.isFinalTerminalStatus())
        assertTrue(AgentGoalStatus.REJECTED.isInactive())
        assertFalse(AgentGoalStatus.REJECTED.isActivePhase())
    }

    @Test
    fun testRejectedCannotResume() {
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R1",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.REJECTED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList()
        )
        
        val resumed = AgentLifecycleReducer.resume(goal)
        assertEquals(AgentGoalStatus.REJECTED, resumed.status)
        assertNull(resumed.lastResumeReason)
    }

    @Test
    fun testRejectedCannotRestartUnlessCancelled() {
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R1",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.REJECTED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList()
        )
        
        val restarted = AgentLifecycleReducer.restart(goal)
        assertEquals(AgentGoalStatus.REJECTED, restarted.status)
        assertEquals(0, restarted.executionGeneration)
    }
}
