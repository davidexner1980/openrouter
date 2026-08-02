package com.david.openassistant.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.david.openassistant.BuildConfig
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Small structured diagnostic ledger for OpenAssistant-owned events.
 *
 * V36: compact, one-line structured events with process-session awareness,
 * truthful redaction counters, and UTF-8 aware content chunking.
 */
open class RuntimeDiagnostics internal constructor(
    private val context: Context?,
    private val diagnosticsDirectory: File?,
    private val researchMonitor: ResearchMonitor?
) : com.david.openassistant.agent.RefreshDiagnostics {
    constructor(context: Context) : this(
        context = context.applicationContext,
        diagnosticsDirectory = File(context.applicationContext.filesDir, DIRECTORY_NAME),
        researchMonitor = ResearchMonitor(context.applicationContext)
    )

    private val executor: ExecutorService = SHARED_EXECUTOR

    init {
        if (context != null && !INITIALIZED.getAndSet(true)) {
            emitAppIdentity(context)
            detectPreviousExit(context)
        }
    }

    private fun emitAppIdentity(context: Context) {
        info(
            event = "app_build_identity",
            component = "app",
            fields = mapOf(
                "application_id" to context.packageName,
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE,
                "build_type" to BuildConfig.BUILD_TYPE,
                "git_sha" to BuildConfig.GIT_SHA,
                "api_level" to Build.VERSION.SDK_INT,
                "device_model" to Build.MODEL,
                "is_emulator" to isEmulator(),
                "boot_id" to DiagnosticEvent.BOOT_SESSION_ID,
                "process_sid" to DiagnosticEvent.PROCESS_SESSION_ID
            )
        )
        info("app_process_started", component = "app")
    }

    private fun detectPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val exits = am?.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            exits?.firstOrNull()?.let { exit ->
                val reason = when (exit.reason) {
                    android.app.ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "signal"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
                    android.app.ApplicationExitInfo.REASON_ANR -> "anr"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
                    android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
                    android.app.ApplicationExitInfo.REASON_OTHER -> "other"
                    else -> "unknown_code_${exit.reason}"
                }
                info(
                    event = "previous_process_exit_observed",
                    component = "app",
                    fields = mapOf(
                        "exit_reason" to reason,
                        "exit_description" to exit.description,
                        "exit_timestamp" to exit.timestamp,
                        "exit_importance" to exit.importance
                    )
                )
            }
        }
    }

    private fun isEmulator(): Boolean =
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86")

    fun withContext(fields: Map<String, Any?>): RuntimeDiagnostics =
        ScopedDiagnostics(this, fields)

    fun section(title: String) {
        val line = "=".repeat(10)
        Log.i(LOGCAT_TAG, "💠 $line $title $line")
    }

    fun startTimer(event: String, component: String = "general", fields: Map<String, Any?> = emptyMap()): DiagnosticTimer =
        DiagnosticTimer(this, event, component, fields)

    override fun info(event: String, fields: Map<String, Any?>) {
        record(level = "INFO", component = "general", event = event, fields = fields, throwable = null)
    }

    fun info(event: String, component: String, fields: Map<String, Any?> = emptyMap()) {
        record(level = "INFO", component = component, event = event, fields = fields, throwable = null)
    }

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

    override fun error(event: String, throwable: Throwable, fields: Map<String, Any?>) {
        record(level = "ERROR", component = "general", event = event, fields = fields, throwable = throwable)
    }

    fun error(event: String, component: String, throwable: Throwable, fields: Map<String, Any?> = emptyMap()) {
        record(level = "ERROR", component = component, event = event, fields = fields, throwable = throwable)
    }

    /**
     * Emits a bounded, redacted content preview event, chunking if necessary.
     */
    fun contentPreview(
        kind: String,
        content: String,
        goalId: String?,
        taskId: String?,
        exchangeId: String? = null,
        extraFields: Map<String, Any?> = emptyMap()
    ) {
        val sanitized = redactDiagnosticText(content)
        val hash = sha256(content)
        val bytes = sanitized.toByteArray(StandardCharsets.UTF_8)
        val totalSize = bytes.size
        val limit = 2000
        val chunkCount = (totalSize + limit - 1) / limit
        val contentId = UUID.randomUUID().toString().take(8)

        for (i in 0 until chunkCount) {
            val start = i * limit
            val end = minOf((i + 1) * limit, totalSize)
            val chunk = String(bytes.sliceArray(start until end), StandardCharsets.UTF_8)
            
            info(
                event = "${kind}_content",
                component = "content",
                fields = extraFields + mapOf(
                    "goal_id" to goalId,
                    "task_id" to taskId,
                    "exchange_id" to exchangeId,
                    "content_id" to contentId,
                    "content_sha256" to hash,
                    "chunk_index" to i,
                    "chunk_total" to chunkCount,
                    "captured_bytes" to totalSize,
                    "preview" to chunk
                )
            )
        }
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
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

        val logcatLine = diagEvent.toLogcatLine()
        when (level) {
            "ERROR" -> Log.e(LOGCAT_TAG, logcatLine, throwable)
            "WARN" -> Log.w(LOGCAT_TAG, logcatLine, throwable)
            "DEBUG" -> Log.d(LOGCAT_TAG, logcatLine)
            else -> Log.i(LOGCAT_TAG, logcatLine)
        }

        researchMonitor?.record(
            category = component,
            event = diagEvent.event,
            level = level,
            correlationId = diagEvent.goalId ?: diagEvent.exchangeId,
            fields = diagEvent.fields
        )

        val jsonLine = diagEvent.toJsonObject().toString() + "\n"
        runCatching {
            executor.execute { 
                if (!appendLine(jsonLine)) {
                    SINK_FAILURE_COUNT.incrementAndGet()
                }
            }
        }.onFailure {
            DROPPED_EVENT_COUNT.incrementAndGet()
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

        val goalId = fields["goal_id"]?.toString()
        val taskId = fields["task_id"]?.toString()
        val workerId = fields["worker_id"]?.toString()
        val exchangeId = fields["exchange_id"]?.toString()
        val operationId = fields["operation_id"]?.toString()
        val durationMs = (fields["duration_ms"] as? Number)?.toLong()
        val outcome = fields["outcome"]?.toString()
        val reasonCode = fields["reason_code"]?.toString()
        val stateBefore = fields["state_before"]?.toString()
        val stateAfter = fields["state_after"]?.toString()

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
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            fields = finalFields
        )
    }

    private fun appendLine(line: String): Boolean = synchronized(FILE_LOCK) {
        return runCatching {
            diagnosticsDirectory?.mkdirs()
            val active = activeLogFile()
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            if (active.exists() && active.length() + bytes.size > MAX_FILE_BYTES) {
                val previous = File(diagnosticsDirectory, PREVIOUS_FILE_NAME)
                if (previous.exists()) previous.delete()
                active.renameTo(previous)
            }
            active.appendText(line, StandardCharsets.UTF_8)
            true
        }.getOrDefault(false)
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
                        FORBIDDEN_FIELDS_DROPPED.incrementAndGet()
                        "<redacted_field>"
                    }
                    rawValue == null -> JSONObject.NULL
                    rawValue is Number || rawValue is Boolean -> rawValue
                    else -> {
                        val text = rawValue.toString()
                        if (text.contains("[REDACTED]")) {
                            PRE_REDACTED_MARKERS_OBSERVED.incrementAndGet()
                        }
                        redactDiagnosticText(text).take(MAX_FIELD_VALUE_LENGTH)
                    }
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
                    append(frame.className.substringAfterLast('.'))
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
    ) : RuntimeDiagnostics(null, null, null) {
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
        override fun close() = stop()
    }

    companion object {
        const val LOGCAT_TAG = "OpenAssistant"
        private const val DIRECTORY_NAME = "diagnostics"
        private const val ACTIVE_FILE_NAME = "runtime.jsonl"
        private const val PREVIOUS_FILE_NAME = "runtime.previous.jsonl"
        private const val MAX_FILE_BYTES = 1024L * 1024L // 1MB
        private const val MAX_FIELDS = 48
        private const val MAX_EVENT_LENGTH = 96
        private const val MAX_FIELD_KEY_LENGTH = 64
        private const val MAX_FIELD_VALUE_LENGTH = 2000
        private val FORBIDDEN_FIELD_NAMES = setOf(
            "api_key", "apikey", "authorization", "cookie", "set_cookie", "credential",
            "password", "private_key", "secret", "token", "access_token", "refresh_token",
            "reasoning", "hidden_reasoning", "prompt", "request_text", "response_text", "message_content"
        )
        private val FILE_LOCK = Any()
        private val INITIALIZED = java.util.concurrent.atomic.AtomicBoolean(false)
        private val SHARED_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenAssistantDiagnostics").apply { isDaemon = true }
        }
        
        private val REDACTION_REPLACEMENTS_APPLIED = AtomicLong(0)
        private val FORBIDDEN_FIELDS_DROPPED = AtomicLong(0)
        private val PRE_REDACTED_MARKERS_OBSERVED = AtomicLong(0)
        private val DROPPED_EVENT_COUNT = AtomicLong(0)
        private val SINK_FAILURE_COUNT = AtomicLong(0)

        fun redactionStats(): Map<String, Long> = mapOf(
            "redaction_replacements_applied" to REDACTION_REPLACEMENTS_APPLIED.get(),
            "forbidden_fields_dropped" to FORBIDDEN_FIELDS_DROPPED.get(),
            "pre_redacted_markers_observed" to PRE_REDACTED_MARKERS_OBSERVED.get(),
            "events_dropped" to DROPPED_EVENT_COUNT.get(),
            "sink_failures" to SINK_FAILURE_COUNT.get()
        )

        internal fun incrementRedactionCount() {
            REDACTION_REPLACEMENTS_APPLIED.incrementAndGet()
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
                match.groupValues.size >= 2 && match.groupValues[1].isNotBlank() -> "${match.groupValues[1]}=[REDACTED]"
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
