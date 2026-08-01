package com.david.openassistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class MissionForegroundPolicyTest {

    @Test
    fun goalStatusIsInactiveIncludesTerminalAndWaitStates() {
        assertTrue(AgentGoalStatus.COMPLETED.isInactive())
        assertTrue(AgentGoalStatus.CANCELLED.isInactive())
        assertTrue(AgentGoalStatus.WAITING_FOR_NETWORK.isInactive())
        assertTrue(AgentGoalStatus.WAITING_FOR_CREDENTIAL.isInactive())
        assertTrue(AgentGoalStatus.BLOCKED.isInactive())
    }

    @Test
    fun goalStatusIsInactiveExcludesActiveStates() {
        assertTrue(!AgentGoalStatus.RUNNING.isInactive())
        assertTrue(!AgentGoalStatus.RESEARCHING.isInactive())
        assertTrue(!AgentGoalStatus.PLANNING.isInactive())
        assertTrue(!AgentGoalStatus.SYNTHESIZING.isInactive())
    }
}
