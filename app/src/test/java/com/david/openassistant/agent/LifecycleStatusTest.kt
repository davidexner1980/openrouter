package com.david.openassistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleStatusTest {
    @Test
    fun testIsActivePhase() {
        assertTrue("PLANNING should be active", AgentGoalStatus.PLANNING.isActivePhase())
        assertTrue("QUEUED should be active", AgentGoalStatus.QUEUED.isActivePhase())
        assertTrue("RUNNING should be active", AgentGoalStatus.RUNNING.isActivePhase())
        assertTrue("RESEARCHING should be active", AgentGoalStatus.RESEARCHING.isActivePhase())
        assertTrue("VERIFYING should be active", AgentGoalStatus.VERIFYING.isActivePhase())
    }
}
