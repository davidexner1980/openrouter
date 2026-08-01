package com.david.openassistant.data.diagnostics

import android.content.Context
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /**
     * Creates a scoped diagnostic ledger that automatically appends the provided
     * fields to every log entry.
     */
    fun withContext(fields: Map<String, Any?>): RuntimeDiagnostics =
        ScopedDiagnostics(this, fields)

    /**
     * Demarcates a major phase transition with a visual separator in Logcat.
     */
    fun section(title: String) {
        val line = "=".repeat(10)
        Log.i(LOGCAT_TAG, "💠 $line $title $line")
    }

    /**
     * Starts a diagnostic timer for a specific event. Call [DiagnosticTimer.stop]
     * to log the final duration.
     */
    fun startTimer(event: String, component: String = "general", fields: Map<String, Any?> = emptyMap()): DiagnosticTimer =
        DiagnosticTimer(this, event, component, fields)

    override fun info(event: String, fields: Map<String, Any?>) {
        record(level = "INFO", component = "general", event = event, fields = fields, throwable = null)
    }

    fun info(event: String, component: String, fields: Map<String, Any?> = emptyMap()) {
        record(level = "INFO", component = component, event = event, fields = fields, throwable = null)
    }

    /**
     * High-frequency developer logging that hits Logcat but is NOT persisted to disk.
     */
    fun debug(event: String, component: String = "general", fields: Map<String, Any?> = emptyMap()) {
        val diagEvent = buildEvent(level = "DEBUG", component = component, event = event, fields = fields, throwable = null)
        Log.d(LOGCAT_TAG, diagEvent.toLogcatLine())
    }

    open fun warning(event: String, fields: Map<String, Any?> = emptyMap()) {
        record(level = "WARN", component = "general", event = event, fields = fields, throwable = null)
    }

    fun warning(event: String, component: String, fields: Map<String, Any?> = emptyMap()) {
        record(level = "WARN", component = component, event = event, fields = fields, throwable = null)
    }

    override fun error(
        event: String,
        throwable: Throwable,
        fields: Map<String, Any?>,
    ) {
        record(level = "ERROR", component = "general", event = event, fields = fields, throwable = throwable)
    }

    fun error(
        event: String,
        component: String,
        throwable: Throwable,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        record(level = "ERROR", component = component, event = event, fields = fields, throwable = throwable)
    }

    fun activeLogFile(): File = File(diagnosticsDirectory!!, ACTIVE_FILE_NAME)

    private fun record(
        level: String,
        component: String,
        event: String,
        fields: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        val diagEvent = buildEvent(level, component, event, fields, throwable)

        // 1. Logcat Sink
        val logcatLine = diagEvent.toLogcatLine()
        when (level) {
            "ERROR" -> Log.e(LOGCAT_TAG, logcatLine, throwable)
            "WARN" -> Log.w(LOGCAT_TAG, logcatLine, throwable)
            "DEBUG" -> Log.d(LOGCAT_TAG, logcatLine)
            else -> Log.i(LOGCAT_TAG, logcatLine)
        }

        // 2. Monitor Sink
        researchMonitor?.record(
            category = component,
            event = diagEvent.event,
            level = level,
            correlationId = diagEvent.goalId ?: diagEvent.exchangeId,
            fields = diagEvent.fields + mapOf(
                "process_seq" to diagEvent.processSequence,
                "uptime" to diagEvent.elapsedRealtimeMs
            )
        )

        // 3. JSONL Sink (Persistent)
        val jsonLine = diagEvent.toJsonObject().toString() + "\n"
        runCatching {
            executor.execute { appendLine(jsonLine) }
        }
    }

    private fun buildEvent(
        level: String,
        component: String,
        event: String,
        fields: Map<String, Any?>,
        throwable: Throwable?,
    ): DiagnosticEvent {
        val safeEvent = event.trim().ifBlank { "unnamed_event" }.take(MAX_EVENT_LENGTH)
        val sanitizedFields = sanitize(fields)
        
        throwable?.openAssistantOrigin()?.let { origin ->
            if (!sanitizedFields.has("error_origin") && sanitizedFields.length() < MAX_FIELDS) {
                sanitizedFields.put("error_origin", origin)
            }
        }

        // Extract well-known IDs from fields for the envelope
        val goalId = fields["goal_id"]?.toString()
        val taskId = fields["task_id"]?.toString()
        val workerId = fields["worker_id"]?.toString()
        val exchangeId = fields["exchange_id"]?.toString()
        val operationId = fields["operation_id"]?.toString()
        val durationMs = (fields["duration_ms"] as? Number)?.toLong()
        val outcome = fields["outcome"]?.toString()
        val reasonCode = fields["reason_code"]?.toString()

        val finalFields = mutableMapOf<String, Any?>()
        sanitizedFields.keys().forEach { key ->
            finalFields[key] = sanitizedFields.get(key)
        }

        return DiagnosticEvent(
            level = level,
            component = component,
            event = safeEvent,
            outcome = outcome,
            reasonCode = reasonCode,
            goalId = goalId,
            taskId = taskId,
            workerId = workerId,
            exchangeId = exchangeId,
            operationId = operationId,
            durationMs = durationMs,
            fields = finalFields
        )
    }

    private fun appendLine(line: String) = synchronized(FILE_LOCK) {
        diagnosticsDirectory?.mkdirs()
        val active = activeLogFile()
        if (active.exists() && active.length() + line.toByteArray(StandardCharsets.UTF_8).size.toLong() > MAX_FILE_BYTES) {
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
                    lowered in FORBIDDEN_FIELD_NAMES -> {
                        incrementForbiddenFieldsDropped()
                        "<redacted>"
                    }
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

    private class ScopedDiagnostics(
        private val parent: RuntimeDiagnostics,
        private val scopedFields: Map<String, Any?>
    ) : RuntimeDiagnostics(null, null) {
        override fun info(event: String, fields: Map<String, Any?>) {
            parent.info(event, "general", scopedFields + fields)
        }

        override fun warning(event: String, fields: Map<String, Any?>) {
            parent.warning(event, "general", scopedFields + fields)
        }

        override fun error(event: String, throwable: Throwable, fields: Map<String, Any?>) {
            parent.error(event, "general", throwable, scopedFields + fields)
        }
    }

    class DiagnosticTimer internal constructor(
        private val diagnostics: RuntimeDiagnostics,
        private val event: String,
        private val component: String,
        private val initialFields: Map<String, Any?>
    ) : Closeable {
        private val startNanos = System.nanoTime()
        private var stopped = false

        fun stop(additionalFields: Map<String, Any?> = emptyMap()) {
            if (stopped) return
            stopped = true
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            diagnostics.info(
                event = event,
                component = component,
                fields = initialFields + additionalFields + mapOf("duration_ms" to durationMs)
            )
        }

        override fun close() {
            stop()
        }
    }

    companion object {
        const val LOGCAT_TAG = "OpenAssistant"
        private const val DIRECTORY_NAME = "diagnostics"
        private const val ACTIVE_FILE_NAME = "runtime.jsonl"
        private const val PREVIOUS_FILE_NAME = "runtime.previous.jsonl"
        private const val MAX_FILE_BYTES = 512L * 1024L
        private const val MAX_FIELDS = 48
        private const val MAX_EVENT_LENGTH = 96
        private const val MAX_FIELD_KEY_LENGTH = 64
        private const val MAX_FIELD_VALUE_LENGTH = 2000
        private const val MAX_ERROR_MESSAGE_LENGTH = 1_000
        private val FORBIDDEN_FIELD_NAMES = setOf(
            "api_key",
            "apikey",
            "authorization",
            "cookie",
            "set_cookie",
            "credential",
            "password",
            "private_key",
            "secret",
            "token",
            "access_token",
            "refresh_token",
            "reasoning",
            "hidden_reasoning",
            "prompt",
            "request_text",
            "response_text",
            "message_content",
        )
        private val FILE_LOCK = Any()
        private val SHARED_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenAssistantDiagnostics").apply { isDaemon = true }
        }
        
        private val REDACTION_COUNT = java.util.concurrent.atomic.AtomicLong(0)
        private val FORBIDDEN_FIELDS_DROPPED = java.util.concurrent.atomic.AtomicLong(0)

        fun redactionStats(): Map<String, Long> = mapOf(
            "secrets_redacted" to REDACTION_COUNT.get(),
            "forbidden_fields_dropped" to FORBIDDEN_FIELDS_DROPPED.get()
        )

        internal fun incrementRedactionCount() {
            REDACTION_COUNT.incrementAndGet()
        }

        internal fun incrementForbiddenFieldsDropped() {
            FORBIDDEN_FIELDS_DROPPED.incrementAndGet()
        }
    }
}

internal fun redactDiagnosticText(value: String): String {
    var redacted = value
    DIAGNOSTIC_SECRET_PATTERNS.forEach { pattern ->
        redacted = pattern.replace(redacted) { match ->
            RuntimeDiagnostics.incrementRedactionCount()
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
