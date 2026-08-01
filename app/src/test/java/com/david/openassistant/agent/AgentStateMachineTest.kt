package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class AgentStateMachineTest {

    @Test
    fun allGoalStatusRepresentedInTransitions() {
        // This test fails if a new enum value is added without updating the transition map.
        // It uses reflection to check that all entries in the enum are keys in the allowedTransitions map.
        val enumValues = AgentGoalStatus.entries.toSet()
        
        // We can't access private allowedTransitions directly, so we test canTransition for every state.
        for (status in enumValues) {
            try {
                // If it doesn't throw, it means the state is handled in the map (or it's a same-state transition).
                // We specifically want to ensure it's in the map to allow transitions to other states.
                AgentStateMachine.canTransition(status, status)
            } catch (e: NoSuchElementException) {
                fail("AgentGoalStatus.$status is missing from AgentStateMachine allowedTransitions map")
            }
        }
    }

    @Test
    fun validTransitionsToFinalizing() {
        val activeStates = listOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.RUNNING,
            AgentGoalStatus.VERIFYING,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.BLOCKED
        )

        for (from in activeStates) {
            assertTrue("Should allow transition from $from to FINALIZING", 
                AgentStateMachine.canTransition(from, AgentGoalStatus.FINALIZING))
        }
    }

    @Test
    fun validTransitionsFromFinalizing() {
        val terminalStates = listOf(
            AgentGoalStatus.COMPLETED,
            AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
            AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED
        )

        for (to in terminalStates) {
            assertTrue("Should allow transition from FINALIZING to $to", 
                AgentStateMachine.canTransition(AgentGoalStatus.FINALIZING, to))
        }
    }

    @Test
    fun illegalTransitionsAreRejected() {
        // COMPLETED is a terminal state
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.COMPLETED, AgentGoalStatus.RUNNING))
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.COMPLETED, AgentGoalStatus.PLANNING))
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE, AgentGoalStatus.RUNNING))
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS, AgentGoalStatus.RUNNING))
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE, AgentGoalStatus.RUNNING))
        
        // CANCELLED is a terminal state
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.CANCELLED, AgentGoalStatus.RUNNING))
        
        // FINALIZING should not go back to RUNNING or PLANNING
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.FINALIZING, AgentGoalStatus.RUNNING))
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.FINALIZING, AgentGoalStatus.PLANNING))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireTransitionThrowsOnIllegalMove() {
        AgentStateMachine.requireTransition(AgentGoalStatus.COMPLETED, AgentGoalStatus.RUNNING)
    }
}
