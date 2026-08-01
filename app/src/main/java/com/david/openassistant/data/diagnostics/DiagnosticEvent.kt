package com.david.openassistant.data.diagnostics

import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Canonical structured diagnostic event for OpenAssistant.
 * 
 * V36 Schema: compact, ordered, and privacy-safe.
 */
data class DiagnosticEvent(
    val schemaVersion: Int = 1,
    val timestampUtc: Long = System.currentTimeMillis(),
    val elapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime(),
    val processSessionId: String = PROCESS_SESSION_ID,
    val processSequence: Long = PROCESS_SEQUENCE.getAndIncrement(),
    val level: String, // INFO, WARN, ERROR, DEBUG
    val component: String,
    val event: String,
    val outcome: String? = null,
    val reasonCode: String? = null,
    val goalId: String? = null,
    val taskId: String? = null,
    val workerId: String? = null,
    val workRequestId: String? = null,
    val workerRunAttempt: Int? = null,
    val leaseGeneration: Int? = null,
    val leaseAttemptId: String? = null,
    val exchangeId: String? = null,
    val operationId: String? = null,
    val searchId: String? = null,
    val sourceReadId: String? = null,
    val toolCallId: String? = null,
    val stateBefore: String? = null,
    val stateAfter: String? = null,
    val durationMs: Long? = null,
    val fields: Map<String, Any?> = emptyMap()
) {
    /**
     * Formats the event for Logcat as a compact, one-line structured string.
     * Shape: OA1 level=LEVEL component=COMP event=EVENT IDs FIELDS
     */
    fun toLogcatLine(): String {
        return buildString {
            append("OA")
            append(schemaVersion)
            append(" level=")
            append(level)
            append(" component=")
            append(component)
            append(" event=")
            append(event)
            
            appendOptional("outcome", outcome)
            appendOptional("reason_code", reasonCode)
            appendOptional("goal_id", goalId)
            appendOptional("task_id", taskId)
            appendOptional("exchange_id", exchangeId)
            appendOptional("operation_id", operationId)
            appendOptional("duration_ms", durationMs)
            
            // Append other standard IDs if present
            appendOptional("worker_id", workerId)
            appendOptional("lease_gen", leaseGeneration)
            appendOptional("state_before", stateBefore)
            appendOptional("state_after", stateAfter)

            // Append fields (already sanitized by the caller/RuntimeDiagnostics)
            fields.entries.sortedBy { it.key }.forEach { (key, value) ->
                if (value != null && value != JSONObject.NULL) {
                    append(' ')
                    append(key)
                    append('=')
                    val str = value.toString()
                    if (str.contains(' ') || str.contains('"')) {
                        append('"')
                        append(str.replace("\"", "\\\""))
                        append('"')
                    } else {
                        append(str)
                    }
                }
            }
        }
    }

    private fun StringBuilder.appendOptional(key: String, value: Any?) {
        if (value != null) {
            append(' ')
            append(key)
            append('=')
            append(value.toString())
        }
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("v", schemaVersion)
            put("ts", timestampUtc)
            put("uptime", elapsedRealtimeMs)
            put("sid", processSessionId)
            put("seq", processSequence)
            put("level", level)
            put("component", component)
            put("event", event)
            putOpt("outcome", outcome)
            putOpt("reason_code", reasonCode)
            putOpt("goal_id", goalId)
            putOpt("task_id", taskId)
            putOpt("worker_id", workerId)
            putOpt("exchange_id", exchangeId)
            putOpt("operation_id", operationId)
            putOpt("duration_ms", durationMs)
            
            if (fields.isNotEmpty()) {
                val f = JSONObject()
                fields.forEach { (k, v) -> f.put(k, v) }
                put("fields", f)
            }
        }
    }

    companion object {
        val PROCESS_SESSION_ID: String = UUID.randomUUID().toString().take(8)
        private val PROCESS_SEQUENCE = AtomicLong(1)
    }
}
