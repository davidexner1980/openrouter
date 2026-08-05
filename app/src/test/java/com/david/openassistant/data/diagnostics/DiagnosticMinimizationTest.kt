package com.david.openassistant.data.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.util.*

class DiagnosticMinimizationTest {

    @Test
    fun testBuildEventFiltering() {
        val diag = RuntimeDiagnostics(null, null, null)
        val method = RuntimeDiagnostics::class.java.getDeclaredMethod(
            "buildEvent",
            String::class.java,
            String::class.java,
            String::class.java,
            Map::class.java,
            Throwable::class.java
        )
        method.isAccessible = true
        
        val inputFields = mapOf(
            "goal_id" to "goal-1",
            "task_id" to "task-1",
            "worker_id" to "worker-1",
            "exchange_id" to "ex-1",
            "lease_gen" to 42,
            "duration_ms" to 100L,
            "other_data" to "val"
        )
        
        val event = method.invoke(diag, "INFO", "comp", "event", inputFields, null) as DiagnosticEvent
        
        // 1. Verify top-level promotion
        assertEquals("goal-1", event.goalId)
        assertEquals("task-1", event.taskId)
        assertEquals("worker-1", event.workerId)
        assertEquals("ex-1", event.exchangeId)
        assertEquals(42, event.leaseGeneration)
        assertEquals(100L, event.durationMs)
        
        // 2. Verify minimization in the fields map
        assertFalse("fields should NOT contain goal_id", event.fields.containsKey("goal_id"))
        assertFalse("fields should NOT contain task_id", event.fields.containsKey("task_id"))
        assertFalse("fields should NOT contain worker_id", event.fields.containsKey("worker_id"))
        assertFalse("fields should NOT contain exchange_id", event.fields.containsKey("exchange_id"))
        assertFalse("fields should NOT contain lease_gen", event.fields.containsKey("lease_gen"))
        assertFalse("fields should NOT contain duration_ms", event.fields.containsKey("duration_ms"))
        
        assertTrue("fields should still contain other_data", event.fields.containsKey("other_data"))
        assertEquals("val", event.fields["other_data"])
    }
}
