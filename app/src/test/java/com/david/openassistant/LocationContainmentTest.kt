package com.david.openassistant

import com.david.openassistant.data.diagnostics.redactResearchMonitorText
import com.david.openassistant.domain.tools.DeviceToolRuntime
import com.david.openassistant.domain.tools.OpenRouterToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationContainmentTest {

    @Test
    fun get_current_location_returns_recoverable_unavailable_result() {
        // Parameter is now nullable in DeviceToolRuntime to support containment testing.
        val runtime = DeviceToolRuntime(null)
        
        val call = OpenRouterToolCall(
            id = "test_call",
            name = "get_current_location",
            argumentsJson = "{}"
        )
        val result = runtime.execute(call)
        val json = JSONObject(result.outputJson)

        assertEquals("unavailable", json.getString("status"))
        assertTrue(json.getString("reason").contains("disabled for privacy"))
        assertTrue(json.getString("reason").contains("city or region"))
        assertEquals("Device location access is disabled.", result.displaySummary)
    }

    @Test
    fun redactResearchMonitorText_redacts_coordinates_in_json_and_text() {
        val rawJson = """{"latitude": 41.8781, "longitude": -87.6298, "version": "1.8.33"}"""
        val redacted = redactResearchMonitorText(rawJson)

        assertFalse("Latitude should be redacted", redacted.contains("41.8781"))
        assertFalse("Longitude should be redacted", redacted.contains("-87.6298"))
        assertTrue("JSON key should be preserved", redacted.contains("\"latitude\""))
        assertTrue("Other values should be preserved", redacted.contains("1.8.33"))
    }

    @Test
    fun redactResearchMonitorText_redacts_coordinates_in_query_params() {
        val rawText = "Requesting data for latitude=41.8781&longitude=-87.6298"
        val redacted = redactResearchMonitorText(rawText)

        assertFalse("Latitude should be redacted", redacted.contains("41.8781"))
        assertFalse("Longitude should be redacted", redacted.contains("-87.6298"))
        assertTrue("Label should be preserved", redacted.contains("latitude="))
    }

    @Test
    fun redactResearchMonitorText_preserves_legitimate_numeric_research() {
        val rawText = "The population increased by 12.5% in 2026. Version 9.3.1 was released."
        val redacted = redactResearchMonitorText(rawText)

        assertTrue("Percentages should be preserved", redacted.contains("12.5%"))
        assertTrue("Years should be preserved", redacted.contains("2026"))
        assertTrue("Versions should be preserved", redacted.contains("9.3.1"))
    }
}
