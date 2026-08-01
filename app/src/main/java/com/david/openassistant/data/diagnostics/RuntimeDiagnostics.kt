package com.david.openassistant.data.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small structured diagnostic ledger for OpenAssistant-owned events.
 *
 * Logcat is intentionally noisy on some Samsung devices, so every important
 * application event is emitted under one stable tag and mirrored to a bounded
 * app-private JSONL file. Fields are metadata only; prompts, response text, and
 * credentials must never be passed to this class.
 */
open class RuntimeDiagnostics internal constructor(
    private val diagnosticsDirectory: File?,
    private val researchMonitor: ResearchMonitor?
) : com.david.openassistant.agent.RefreshDiagnostics {
    constructor(context: Context) : this(
        diagnosticsDirectory = File(context.applicationContext.filesDir, DIRECTORY_NAME),
        researchMonitor = ResearchMonitor(context.applicationContext)
    )

    private val executor: ExecutorService = SHARED_EXECUTOR

    override fun info(event: String, fields: Map<String, Any?>) {
        record(level = "INFO", event = event, fields = fields, throwable = null)
    }

    open fun warning(event: String, fields: Map<String, Any?> = emptyMap()) {
        record(level = "WARN", event = event, fields = fields, throwable = null)
    }

    override fun error(
        event: String,
        throwable: Throwable,
        fields: Map<String, Any?>,
    ) {
        record(level = "ERROR", event = event, fields = fields, throwable = throwable)
    }

    fun activeLogFile(): File = File(diagnosticsDirectory!!, ACTIVE_FILE_NAME)

    private fun record(
        level: String,
        event: String,
        fields: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        val safeEvent = event.trim().ifBlank { "unnamed_event" }.take(MAX_EVENT_LENGTH)
        val safeFields = sanitize(fields)
        throwable?.openAssistantOrigin()?.let { origin ->
            if (!safeFields.has("error_origin") && safeFields.length() < MAX_FIELDS) {
                safeFields.put("error_origin", origin)
            }
        }
        val logcatText = buildString {
            append(safeEvent)
            if (safeFields.length() > 0) {
                append(' ')
                append(safeFields.toString())
            }
            throwable?.let {
                append(" error=")
                append(it::class.java.simpleName)
                it.message?.takeIf(String::isNotBlank)?.let { message ->
                    append(':')
                    append(redactDiagnosticText(message).take(MAX_ERROR_MESSAGE_LENGTH))
                }
            }
        }
        when (level) {
            "ERROR" -> Log.e(LOGCAT_TAG, logcatText)
            "WARN" -> Log.w(LOGCAT_TAG, logcatText)
            else -> Log.i(LOGCAT_TAG, logcatText)
        }

        val monitorFields = linkedMapOf<String, Any?>()
        safeFields.keys().forEach { key -> monitorFields[key] = safeFields.opt(key) }
        throwable?.let { error ->
            monitorFields["error_type"] = error::class.java.name
            monitorFields["error_message"] = error.message.orEmpty()
            monitorFields["stack_trace"] = Log.getStackTraceString(error)
        }
        researchMonitor!!.record(
            category = "runtime",
            event = safeEvent,
            level = level,
            correlationId = monitorFields["goal_id"]?.toString()
                ?: monitorFields["response_id"]?.toString(),
            fields = monitorFields,
        )

        val line = JSONObject()
            .put("timestamp_ms", System.currentTimeMillis())
            .put("level", level)
            .put("event", safeEvent)
            .put("fields", safeFields)
            .apply {
                if (throwable != null) {
                    put("error_type", throwable::class.java.name)
                    put(
                        "error_message",
                        redactDiagnosticText(throwable.message.orEmpty()).take(MAX_ERROR_MESSAGE_LENGTH),
                    )
                }
            }
            .toString() + "\n"

        runCatching {
            executor.execute { appendLine(line) }
        }
    }

    private fun appendLine(line: String) = synchronized(FILE_LOCK) {
        diagnosticsDirectory!!.mkdirs()
        val active = activeLogFile()
        if (active.exists() && active.length() + line.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES) {
            val previous = File(diagnosticsDirectory, PREVIOUS_FILE_NAME)
            if (previous.exists()) previous.delete()
            active.renameTo(previous)
        }
        active.appendText(line, StandardCharsets.UTF_8)
    }

    private fun sanitize(fields: Map<String, Any?>): JSONObject = JSONObject().apply {
        fields.entries
            .sortedBy { it.key }
            .take(MAX_FIELDS)
            .forEach { (rawKey, rawValue) ->
                val key = rawKey.trim().take(MAX_FIELD_KEY_LENGTH)
                if (key.isBlank()) return@forEach
                val lowered = key.lowercase()
                val value = when {
                    lowered in FORBIDDEN_FIELD_NAMES -> "<redacted>"
                    rawValue == null -> JSONObject.NULL
                    rawValue is Number || rawValue is Boolean -> rawValue
                    else -> redactDiagnosticText(rawValue.toString()).take(MAX_FIELD_VALUE_LENGTH)
                }
                put(key, value)
            }
    }

    private fun Throwable.openAssistantOrigin(): String? {
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = this
        while (current != null && seen.add(current)) {
            current.stackTrace.firstOrNull { frame ->
                frame.className.startsWith("com.david.openassistant")
            }?.let { frame ->
                return buildString {
                    append(frame.className)
                    append('#')
                    append(frame.methodName)
                    if (frame.lineNumber > 0) {
                        append(':')
                        append(frame.lineNumber)
                    }
                }.take(MAX_FIELD_VALUE_LENGTH)
            }
            current = current.cause
        }
        return null
    }

    companion object {
        const val LOGCAT_TAG = "OpenAssistant"
        private const val DIRECTORY_NAME = "diagnostics"
        private const val ACTIVE_FILE_NAME = "runtime.jsonl"
        private const val PREVIOUS_FILE_NAME = "runtime.previous.jsonl"
        private const val MAX_FILE_BYTES = 512L * 1024L
        private const val MAX_FIELDS = 32
        private const val MAX_EVENT_LENGTH = 96
        private const val MAX_FIELD_KEY_LENGTH = 64
        private const val MAX_FIELD_VALUE_LENGTH = 512
        private const val MAX_ERROR_MESSAGE_LENGTH = 1_000
        private val FORBIDDEN_FIELD_NAMES = setOf(
            "api_key",
            "apikey",
            "authorization",
            "credential",
            "prompt",
            "request_text",
            "response_text",
            "message_content",
        )
        private val FILE_LOCK = Any()
        private val SHARED_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenAssistantDiagnostics").apply { isDaemon = true }
        }
    }
}

internal fun redactDiagnosticText(value: String): String {
    var redacted = value
    DIAGNOSTIC_SECRET_PATTERNS.forEach { pattern ->
        redacted = pattern.replace(redacted) { match ->
            when {
                match.value.startsWith("Bearer", ignoreCase = true) -> "Bearer [REDACTED]"
                match.groupValues.size >= 2 && match.groupValues[1].isNotBlank() -> {
                    "${match.groupValues[1]}=[REDACTED]"
                }
                else -> "[REDACTED]"
            }
        }
    }
    return redacted
}

private val DIAGNOSTIC_SECRET_PATTERNS = listOf(
    Regex("(?i)\\bBearer\\s+[^\\s,;]+"),
    Regex("\\bsk-or(?:-v1)?-[A-Za-z0-9_-]{8,}\\b"),
    Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
    Regex("(?i)\\b(api[_-]?key|authorization|access[_-]?token|token)=([^&\\s]+)"),
)
