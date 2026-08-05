package com.david.openassistant.data.diagnostics

import com.david.openassistant.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticProvenanceTest {

    @Test
    fun testDiagnosticEventIncludesVersionProvenance() {
        val event = DiagnosticEvent(
            level = "INFO",
            component = "test",
            event = "test_event"
        )
        
        // 1. Check data class defaults
        assertEquals(BuildConfig.VERSION_NAME, event.versionName)
        assertEquals(BuildConfig.VERSION_CODE, event.versionCode)
        
        // 2. Check JSON output
        val json = event.toJsonObject()
        assertEquals(BuildConfig.VERSION_NAME, json.getString("vn"))
        assertEquals(BuildConfig.VERSION_CODE, json.getInt("vc"))
        
        // 3. Check Logcat line
        val line = event.toLogcatLine()
        assertTrue("Logcat line should contain version name", line.contains("vn=${BuildConfig.VERSION_NAME}"))
        assertTrue("Logcat line should contain version code", line.contains("vc=${BuildConfig.VERSION_CODE}"))
    }
    
    @Test
    fun testENVELOPE_FIELDS_FiltersVersionKeys() {
        val event = DiagnosticEvent(
            level = "INFO",
            component = "test",
            event = "test_event",
            fields = mapOf(
                "version_name" to "override",
                "other" to "keep"
            )
        )
        
        val json = event.toJsonObject()
        val fields = json.optJSONObject("fields")
        assertNotNull(fields)
        assertFalse("fields should NOT contain version_name as it is in ENVELOPE_FIELDS", fields!!.has("version_name"))
        assertTrue("fields should contain other", fields!!.has("other"))
    }

    @Test
    fun testDiagnosticEventRedundancyMinimization() {
        val event = DiagnosticEvent(
            level = "INFO",
            component = "test",
            event = "test_event",
            goalId = "goal-1",
            taskId = "task-1",
            fields = mapOf(
                "goal_id" to "goal-1",
                "task_id" to "task-1",
                "vn" to "override",
                "other" to "value"
            )
        )
        
        val json = event.toJsonObject()
        val fields = json.optJSONObject("fields")
        
        assertNotNull(fields)
        // Existing behavior of toJsonObject() filters out ENVELOPE_FIELDS from the 'fields' map in the JSON
        assertFalse("details should NOT contain goal_id", fields!!.has("goal_id"))
        assertFalse("details should NOT contain task_id", fields!!.has("task_id"))
        assertFalse("details should NOT contain vn", fields!!.has("vn"))
        assertTrue("details should contain other", fields!!.has("other"))
    }

    @Test
    fun testRuntimeDiagnosticsBuildEventPopulatesVersion() {
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
        
        val event = method.invoke(diag, "INFO", "comp", "event", emptyMap<String, Any?>(), null) as DiagnosticEvent
        
        assertEquals(BuildConfig.VERSION_NAME, event.versionName)
        assertEquals(BuildConfig.VERSION_CODE, event.versionCode)
    }
}
