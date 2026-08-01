package com.david.openassistant.domain.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

private const val MAX_DIAGNOSTIC_EVENTS = 200

/**
 * Read-only self-observation tool over the metadata-only runtime ledger.
 * Prompt text, response bodies, and credentials are never written to this
 * ledger, so the agent can inspect its own failures without gaining access to
 * private conversation content or arbitrary device files.
 */
object RuntimeDiagnosticToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "inspect_runtime_diagnostics",
            displayName = "Runtime diagnostics inspector",
            description = "Inspect recent OpenAssistant-owned runtime events, warnings, and failures from the bounded private diagnostic ledger. Use this to diagnose startup, streaming, persistence, OpenRouter, tool, or autonomous-worker problems. Message bodies and credentials are excluded.",
            parameters = listOf(
                ToolParameter(
                    name = "limit",
                    description = "Optional number of newest events from 1 to 200. Defaults to 80.",
                    required = false,
                ),
            ),
        ),
    )

    fun handles(name: String): Boolean = name == "inspect_runtime_diagnostics"
}

class RuntimeDiagnosticToolRuntime(context: Context) {
    private val diagnosticsDirectory = File(context.applicationContext.filesDir, "diagnostics")

    fun execute(call: OpenRouterToolCall): ToolExecutionResult {
        if (!RuntimeDiagnosticToolCatalog.handles(call.name)) {
            throw ToolValidationException("Unknown runtime diagnostic tool: ${call.name}")
        }
        val arguments = parseToolArguments(call.argumentsJson)
        val limit = arguments.optInt("limit", 80).coerceIn(1, MAX_DIAGNOSTIC_EVENTS)
        val events = readEvents(limit)
        val levelCounts = linkedMapOf<String, Int>()
        val eventCounts = linkedMapOf<String, Int>()
        events.forEach { event ->
            val level = event.optString("level", "UNKNOWN")
            val name = event.optString("event", "unnamed_event")
            levelCounts[level] = (levelCounts[level] ?: 0) + 1
            eventCounts[name] = (eventCounts[name] ?: 0) + 1
        }
        val payload = JSONObject()
            .put("returned", events.size)
            .put("level_counts", levelCounts.toJsonObject())
            .put(
                "event_counts",
                eventCounts.entries
                    .sortedByDescending { it.value }
                    .take(30)
                    .associate { it.key to it.value }
                    .toJsonObject(),
            )
            .put("events", JSONArray(events))
            .put("privacy_note", "Prompts, response bodies, and credentials are excluded from this ledger.")
        val errorCount = events.count { it.optString("level") == "ERROR" }
        val warningCount = events.count { it.optString("level") == "WARN" }
        return ToolExecutionResult(
            outputJson = payload.toString(),
            displaySummary = "Inspected ${events.size} runtime event(s): $errorCount error(s), $warningCount warning(s).",
        )
    }

    private fun readEvents(limit: Int): List<JSONObject> {
        val previous = File(diagnosticsDirectory, "runtime.previous.jsonl")
        val active = File(diagnosticsDirectory, "runtime.jsonl")
        val lines = buildList {
            if (previous.isFile) addAll(readBoundedLines(previous))
            if (active.isFile) addAll(readBoundedLines(active))
        }
        return lines.asReversed()
            .asSequence()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .take(limit)
            .toList()
            .asReversed()
    }

    private fun readBoundedLines(file: File): List<String> = runCatching {
        file.readLines(StandardCharsets.UTF_8).takeLast(MAX_DIAGNOSTIC_EVENTS)
    }.getOrDefault(emptyList())
    private fun Map<String, Int>.toJsonObject(): JSONObject = JSONObject().apply {
        this@toJsonObject.forEach { (key, value) -> put(key, value) }
    }

}
