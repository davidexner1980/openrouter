package com.david.openassistant.domain.tools

import android.content.Context
import org.json.JSONObject

object DeviceToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "get_current_location",
            displayName = "Device location",
            description = "Retrieve the current approximate location context of the Android device. This tool is currently disabled for privacy. If geographic context is required, ask the user to provide a specific city or region name.",
            parameters = emptyList(),
        ),
    )

    fun handles(name: String): Boolean = name == "get_current_location"
}

class DeviceToolRuntime(private val context: Context?) {

    fun execute(call: OpenRouterToolCall): ToolExecutionResult {
        return when (call.name) {
            "get_current_location" -> getCurrentLocation()
            else -> throw ToolValidationException("Unsupported device tool: ${call.name}")
        }
    }

    private fun getCurrentLocation(): ToolExecutionResult {
        val resultJson = JSONObject()
            .put("status", "unavailable")
            .put("reason", "Direct device location access is disabled for privacy. Research can continue using a specific city or region name supplied by the user if geographic context is required.")

        return ToolExecutionResult(
            outputJson = resultJson.toString(),
            displaySummary = "Device location access is disabled.",
        )
    }
}
