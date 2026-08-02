package com.david.openassistant.agent
import org.junit.Test
import java.io.File
import org.junit.Assert.assertNotNull

class CopyTest {
    @Test
    fun testCopy() {
        val goal = AgentGoal(id="test", conversationId="c", userRequest="r", title="t", objective="o", finalOutputDescription="f", status=AgentGoalStatus.QUEUED, plannerModelId="p", executionModelId="e", tasks=emptyList())
        val newLease = AgentExecutionLease("w", "s", "t", "a", 1, 1L, 1L)
        val updated = goal.copy(executionLease = newLease)
        println("updated executionLease: " + updated.executionLease)
        assertNotNull(updated.executionLease)
    }
}
