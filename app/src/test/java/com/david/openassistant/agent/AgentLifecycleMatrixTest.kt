package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLifecycleMatrixTest {

    @Test
    fun testExhaustiveStatusClassification() {
        val statuses = AgentGoalStatus.entries
        
        // This test ensures that every status has a clear semantic classification
        // and doesn't fall through set-based logic unexpectedly.
        
        statuses.forEach { status ->
            val isInactive = status.isInactive()
            val isActivePhase = status.isActivePhase()
            val isFinalTerminal = status.isFinalTerminalStatus()
            
            // Invariants
            if (isFinalTerminal) {
                assertTrue("Final terminal status $status must be inactive", isInactive)
                assertFalse("Final terminal status $status cannot be active phase", isActivePhase)
            }
            
            if (isActivePhase) {
                assertFalse("Active phase $status cannot be inactive", isInactive)
                assertFalse("Active phase $status cannot be final terminal", isFinalTerminal)
            }

            // Specific semantic checks
            when (status) {
                AgentGoalStatus.PLANNING,
                AgentGoalStatus.RESEARCHING,
                AgentGoalStatus.RETRIEVING,
                AgentGoalStatus.EXTRACTING,
                AgentGoalStatus.VALIDATING,
                AgentGoalStatus.SYNTHESIZING,
                AgentGoalStatus.RECOVERING,
                AgentGoalStatus.RUNNING,
                AgentGoalStatus.VERIFYING,
                AgentGoalStatus.FINALIZING -> {
                    assertTrue("Status $status should be an active phase", isActivePhase)
                    assertFalse("Status $status should not be inactive", isInactive)
                }
                
                AgentGoalStatus.QUEUED -> {
                    assertTrue("QUEUED should be active phase", isActivePhase)
                    assertFalse("QUEUED should not be inactive", isInactive)
                }

                AgentGoalStatus.COMPLETED,
                AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
                AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
                AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
                AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
                AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
                AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
                AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
                AgentGoalStatus.FAILED,
                AgentGoalStatus.REJECTED -> {
                    assertTrue("Status $status should be inactive", isInactive)
                    if (status != AgentGoalStatus.FAILED && status != AgentGoalStatus.REJECTED) {
                        assertTrue("Status $status should be final terminal", isFinalTerminal)
                    }
                }

                AgentGoalStatus.CANCELLED -> {
                    assertTrue("CANCELLED should be inactive", isInactive)
                    assertFalse("CANCELLED should not be final terminal (it is resumable)", isFinalTerminal)
                }

                AgentGoalStatus.CANCELLING -> {
                    // CANCELLING is inactive because it shouldn't be scheduled,
                    // but it is not yet "terminal" in the user sense until it becomes CANCELLED.
                    assertTrue("CANCELLING should be inactive", isInactive)
                    assertFalse("CANCELLING is not active phase", isActivePhase)
                }

                AgentGoalStatus.PAUSED,
                AgentGoalStatus.WAITING_FOR_NETWORK,
                AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                AgentGoalStatus.WAITING_FOR_USER,
                AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
                AgentGoalStatus.BLOCKED,
                AgentGoalStatus.BLOCKED_NEEDS_ACTION -> {
                    assertTrue("Status $status should be inactive", isInactive)
                    assertFalse("Status $status is not final terminal", isFinalTerminal)
                }
            }
        }
    }
}
