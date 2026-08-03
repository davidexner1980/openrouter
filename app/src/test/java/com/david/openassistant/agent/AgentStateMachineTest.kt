package com.david.openassistant.agent

import org.junit.Test
import org.junit.Assert.assertTrue

class AgentStateMachineTest {
    @Test
    fun testAllActiveStatesToRecovering() {
        val activeStates = listOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.RUNNING,
            AgentGoalStatus.VERIFYING,
            AgentGoalStatus.RESEARCHING,
            AgentGoalStatus.RETRIEVING,
            AgentGoalStatus.EXTRACTING,
            AgentGoalStatus.VALIDATING,
            AgentGoalStatus.SYNTHESIZING
        )
        for (state in activeStates) {
            val allowed = AgentStateMachine.canTransition(state, AgentGoalStatus.RECOVERING)
            org.junit.Assert.assertEquals("$state -> RECOVERING failed", true, allowed)
        }
    }

    @Test
    fun testQueuedToRunningTransition() {
        assertTrue("QUEUED -> RUNNING should be allowed",
            AgentStateMachine.canTransition(AgentGoalStatus.QUEUED, AgentGoalStatus.RUNNING))
    }

    @Test
    fun testRecoveringToQueuedTransition() {
        assertTrue("RECOVERING -> QUEUED should be allowed",
            AgentStateMachine.canTransition(AgentGoalStatus.RECOVERING, AgentGoalStatus.QUEUED))
    }
}
