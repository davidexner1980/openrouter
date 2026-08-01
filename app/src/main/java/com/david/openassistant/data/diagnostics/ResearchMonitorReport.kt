package com.david.openassistant.data.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

internal const val MAX_MONITOR_CAPTURE_TEXT_CHARS = 16_384

internal data class ResearchMonitorReportMetadata(
    val sessionId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val appVersion: String,
    val device: String,
    val androidVersion: String,
    val monitoringStillActive: Boolean,
    val traceWasCapped: Boolean,
    val reportKind: String = "snapshot",
    val versionCode: Int = -1,
    val apkSha256: String = "unknown",
    val installationTimestamp: Long = 0L,
    val targetClassification: String = "unknown",
    val apiLevel: Int = -1,
)

private fun removeReasoningFields(obj: JSONObject) {
    val keys = obj.keys().asSequence().toList()
    keys.forEach { key ->
        if (key == "reasoning" || key == "reasoning_details" || 
            key == "latitude" || key == "longitude" || key == "altitude"
        ) {
            obj.put(key, "[EXCLUDED FROM MONITOR]")
        } else {
            val value = obj.opt(key)
            if (value is JSONObject) removeReasoningFields(value)
            else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) removeReasoningFields(item)
                }
            }
        }
    }
}

/**
 * Removes credentials and binary attachment bodies without removing the
 * prompts, model responses, searches, URLs, tool arguments, or tool results
 * that make a monitor report useful.
 */
fun redactResearchMonitorText(value: String): String {
    val lines = value.split("\n")
    val redactedLines = lines.map { line ->
        val trimmed = line.trim()
        val dataPrefix = "data:"
        val contentToRedact = if (trimmed.startsWith(dataPrefix)) {
            val jsonPart = trimmed.substring(dataPrefix.length).trim()
            if (jsonPart.startsWith("{")) {
                val redactedJson = runCatching {
                    val json = JSONObject(jsonPart)
                    removeReasoningFields(json)
                    json.toString()
                }.getOrDefault(jsonPart)
                "data: $redactedJson"
            } else {
                line
            }
        } else if (trimmed.startsWith("{")) {
            runCatching {
                val json = JSONObject(trimmed)
                removeReasoningFields(json)
                json.toString()
            }.getOrDefault(line)
        } else {
            line
        }

        var redacted = MONITOR_SECRET_HEADER_PATTERN.replace(contentToRedact) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }
        MONITOR_SECRET_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted) { match ->
                when {
                    match.value.startsWith("Bearer", ignoreCase = true) -> "Bearer [REDACTED]"
                    match.groupValues.size >= 2 && match.groupValues[1].isNotBlank() -> {
                        val prefix = match.groupValues[1]
                        if (prefix.trimStart().startsWith("\"")) {
                            "$prefix\"[REDACTED]\""
                        } else {
                            "$prefix=[REDACTED]"
                        }
                    }
                    else -> "[REDACTED]"
                }
            }
        }
        redacted = MONITOR_DATA_URL_PATTERN.replace(redacted) { match ->
            "[BINARY DATA URL REDACTED: ${match.value.length} characters]"
        }
        redacted
    }
    
    return redactedLines.joinToString("\n")
}

internal object ResearchMonitorReportWriter {
    fun write(
        traceFile: File,
        reportFile: File,
        metadata: ResearchMonitorReportMetadata,
    ) {
        val summary = summarize(traceFile, metadata)
        reportFile.parentFile?.mkdirs()
        val temporaryReport = File(
            reportFile.parentFile,
            ".${reportFile.name}.${UUID.randomUUID()}.tmp",
        )
        try {
            temporaryReport.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.appendLine("# OpenAssistant research monitor report")
                writer.appendLine()
                writer.appendLine("This report is a passive flight-recorder trace of the work initiated in the app. The monitor did not choose a topic, inject a prompt, simplify the investigation, or change model/tool routing.")
                writer.appendLine()
                writer.appendLine("## Session")
                writer.appendLine()
                writer.appendLine("- Session ID: `${metadata.sessionId}`")
                writer.appendLine("- Started: ${formatTimestamp(metadata.startedAt)}")
                writer.appendLine("- Reported through: ${formatTimestamp(metadata.finishedAt)}")
                writer.appendLine("- Monitor still active after this snapshot: ${metadata.monitoringStillActive}")
                writer.appendLine("- App version: ${metadata.appVersion}")
                writer.appendLine("- Version code: ${metadata.versionCode}")
                writer.appendLine("- APK SHA-256: `${metadata.apkSha256}`")
                writer.appendLine("- First installed: ${formatTimestamp(metadata.installationTimestamp)}")
                writer.appendLine("- Target classification: ${metadata.targetClassification}")
                writer.appendLine("- Device: ${metadata.device}")
                writer.appendLine("- Android: ${metadata.androidVersion}")
                writer.appendLine("- API Level: ${metadata.apiLevel}")
                writer.appendLine("- Trace storage cap reached: ${metadata.traceWasCapped}")
                writer.appendLine()
                writer.appendLine("## Report artifact")
                writer.appendLine()
                writer.appendLine("- Artifact state: **COMPLETE**")
                writer.appendLine("- Report kind: `${metadata.reportKind}`")
                writer.appendLine("- A complete artifact is not a claim that the research mission completed successfully. Unfinished provider calls and other open work at this report boundary are indexed below.")
                writer.appendLine("- The final Markdown filename becomes visible only after the complete report has been flushed and atomically committed.")
                writer.appendLine()
                writer.appendLine("Credentials, authorization headers, cookies, access tokens, OpenRouter-style secret keys, and base64 attachment bodies are redacted. User requests, provider payloads, provider responses, search terms, source URLs, tool arguments, tool results, recovery decisions, and failures remain visible. Any field larger than $MAX_MONITOR_CAPTURE_TEXT_CHARS characters carries an explicit truncation marker.")
                writer.appendLine()
                writer.appendLine("## Deterministic index")
                writer.appendLine()
                writer.appendLine("- Provenance identity: ${summary.provenanceIdentity}")
                if (summary.provenanceIdentity == "MIXED_VERSION") {
                    writer.appendLine("- **WARNING**: This report contains events from multiple app versions and is rejected from release certification.")
                    summary.firstMismatchEvent?.let { writer.appendLine("- First mismatch event: $it") }
                }
                writer.appendLine("- Total events: ${summary.totalEvents}")
                writer.appendLine("- Errors and recoverable issues: ${summary.issueEvents.size}")
                writer.appendLine("- OpenRouter requests: ${summary.providerRequests}")
                writer.appendLine("- OpenRouter terminal outcomes: ${summary.providerOutcomes}")
                writer.appendLine("- Unfinished OpenRouter requests at report boundary: ${summary.unfinishedProviderRequests}")
                writer.appendLine("- Tool calls started: ${summary.toolCalls}")
                writer.appendLine("- Public-web searches: ${summary.webSearches}")
                writer.appendLine("- Public-web fetches: ${summary.webFetches}")
                writer.appendLine("- Rabbit-hole iterations: ${summary.rabbitHoleIterations}")
                writer.appendLine("- Discovered leads: ${summary.discoveredLeads}")
                summary.categoryCounts.toSortedMap().forEach { (category, count) ->
                    writer.appendLine("- Category `$category`: $count event(s)")
                }
                writer.appendLine()
                writer.appendLine("## Errors and recoverable issues")
                writer.appendLine()
                if (summary.issueEvents.isEmpty()) {
                    writer.appendLine("No error, warning, cancellation, timeout, or recovery event was recorded.")
                } else {
                    summary.issueEvents.forEach { issue ->
                        writer.appendLine("- ${formatTimestamp(issue.timestampMs)} — `${issue.category}/${issue.event}` (${issue.level}): ${issue.summary}")
                    }
                }
                writer.appendLine()
                writer.appendLine("## Complete chronological trace")
                writer.appendLine()
                var ordinal = 0
                traceFile.forEachLine(StandardCharsets.UTF_8) { rawLine ->
                    ordinal += 1
                    val event = runCatching { JSONObject(rawLine) }.getOrNull()
                    if (event == null) {
                        writer.appendLine("### ${ordinal.toString().padStart(6, '0')} — unreadable trace line")
                        writer.appendLine()
                        writer.appendLine("~~~~text")
                        writer.appendLine(rawLine.replace("~~~~", "~ ~ ~ ~"))
                        writer.appendLine("~~~~")
                        writer.appendLine()
                        return@forEachLine
                    }
                    val timestamp = event.optLong("timestamp_ms")
                    val category = event.optString("category", "unknown")
                    val name = event.optString("event", "unknown")
                    writer.appendLine("### ${ordinal.toString().padStart(6, '0')} — ${formatTimestamp(timestamp)} — `$category/$name`")
                    writer.appendLine()
                    writer.appendLine("- Level: ${event.optString("level", "INFO")}")
                    event.optString("correlation_id").takeIf { it.isNotBlank() && it != "null" }?.let { correlation ->
                        writer.appendLine("- Correlation ID: `$correlation`")
                    }
                    event.optString("event_id").takeIf { it.isNotBlank() }?.let { eventId ->
                        writer.appendLine("- Event ID: `$eventId`")
                    }
                    writer.appendLine()
                    writer.appendLine("~~~~json")
                    val details = event.optJSONObject("details") ?: JSONObject()
                    val displayDetails = if (category == "provider" && (name == "request" || name == "response" || name == "failure")) {
                        val compact = JSONObject()
                        details.keys().forEach { key ->
                            if (key == "request_body" || key == "response_body") {
                                val body = details.optString(key)
                                compact.put(key, "[COMPACTED: ${body.length} characters; full body remains in runtime.jsonl]")
                            } else {
                                compact.put(key, details.opt(key))
                            }
                        }
                        compact
                    } else {
                        details
                    }
                    writer.appendLine(displayDetails.toString(2).replace("~~~~", "~ ~ ~ ~"))
                    writer.appendLine("~~~~")
                    writer.appendLine()
                }
            }
            replaceAtomically(temporaryReport, reportFile)
        } finally {
            if (temporaryReport.exists()) temporaryReport.delete()
        }
    }

    private fun replaceAtomically(temporaryReport: File, reportFile: File) {
        val atomicMove = runCatching {
            Files.move(
                temporaryReport.toPath(),
                reportFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        if (atomicMove.isFailure) {
            Files.move(
                temporaryReport.toPath(),
                reportFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun summarize(traceFile: File, metadata: ResearchMonitorReportMetadata): MonitorReportSummary {
        var total = 0
        var uncorrelatedProviderRequests = 0
        var uncorrelatedPendingProviderRequests = 0
        val correlatedProviderRequestIds = mutableSetOf<String>()
        val terminalProviderRequestIds = mutableSetOf<String>()
        var toolCalls = 0
        var webSearches = 0
        var webFetches = 0
        var rabbitHoleIterations = 0
        var discoveredLeads = 0
        val discoveredLeadUrls = mutableSetOf<String>()
        var lastEventTimestamp = 0L
        val categoryCounts = linkedMapOf<String, Int>()
        val issues = mutableListOf<MonitorIssueSummary>()
        val pendingProviderRequests = linkedMapOf<String, PendingProviderRequest>()
        
        var provenanceIdentity = "SINGLE_VERSION"
        var firstMismatchEvent: String? = null

        traceFile.forEachLine(StandardCharsets.UTF_8) { line ->
            val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            total += 1
            val timestampMs = event.optLong("timestamp_ms")
            lastEventTimestamp = maxOf(lastEventTimestamp, timestampMs)
            val category = event.optString("category", "unknown")
            val name = event.optString("event", "unknown")
            val level = event.optString("level", "INFO")
            val details = event.optJSONObject("details") ?: JSONObject()
            val correlationId = event.optString("correlation_id").trim()
            
            // Provenance check
            if (provenanceIdentity == "SINGLE_VERSION") {
                val eventVersionName = details.optString("app_version").ifBlank { details.optString("version_name") }
                val eventVersionCode = details.optInt("version_code", -1)
                
                if (eventVersionName.isNotBlank() && eventVersionName != metadata.appVersion) {
                    provenanceIdentity = "MIXED_VERSION"
                    firstMismatchEvent = "ordinal $total: expected ${metadata.appVersion}, found $eventVersionName ($category/$name)"
                } else if (eventVersionCode != -1 && eventVersionCode != metadata.versionCode) {
                    provenanceIdentity = "MIXED_VERSION"
                    firstMismatchEvent = "ordinal $total: expected code ${metadata.versionCode}, found $eventVersionCode ($category/$name)"
                }
            }

            categoryCounts[category] = (categoryCounts[category] ?: 0) + 1
            if (category == "provider" && name == "request") {
                if (correlationId.isNotBlank()) {
                    correlatedProviderRequestIds += correlationId
                    pendingProviderRequests.putIfAbsent(
                        correlationId,
                        PendingProviderRequest(
                            timestampMs = timestampMs,
                            operation = details.optString("operation").trim(),
                            endpoint = details.optString("endpoint").trim(),
                        ),
                    )
                } else {
                    uncorrelatedProviderRequests += 1
                    uncorrelatedPendingProviderRequests += 1
                }
            }
            val providerTerminalEvent = category == "provider" && name in setOf(
                "response",
                "failure",
                "cancelled",
                "cancellation_timeout",
            )
            if (providerTerminalEvent) {
                if (correlationId.isNotBlank()) {
                    if (correlationId in correlatedProviderRequestIds) {
                        terminalProviderRequestIds += correlationId
                        pendingProviderRequests.remove(correlationId)
                    }
                } else if (uncorrelatedPendingProviderRequests > 0) {
                    uncorrelatedPendingProviderRequests -= 1
                }
            }
            if (category == "runtime" && name == "exchange_terminal_updated") {
                val exchangeId = details.optString("exchangeId")
                    .ifBlank { details.optString("exchange_id") }
                    .trim()
                if (exchangeId in correlatedProviderRequestIds) {
                    terminalProviderRequestIds += exchangeId
                    pendingProviderRequests.remove(exchangeId)
                }
            }
            if (category == "research" && name == "rabbit_hole_iteration") {
                rabbitHoleIterations = maxOf(rabbitHoleIterations, details.optInt("iteration"))
            }
            if (category == "tool" && name == "call_started") {
                toolCalls += 1
                when (details.optString("tool_name")) {
                    "public_web_search" -> webSearches += 1
                    "public_web_fetch" -> webFetches += 1
                }
            }
            if (category == "tool" && name == "call_completed") {
                val output = details.optString("output_json")
                if (output.isNotBlank()) {
                    val payload = runCatching { JSONObject(output) }.getOrNull()
                    payload?.optJSONArray("discovered_leads")?.let { leads ->
                        for (index in 0 until leads.length()) {
                            val lead = leads.optJSONObject(index) ?: continue
                            val url = lead.optString("url").trim()
                            if (url.isNotBlank()) discoveredLeadUrls += url
                        }
                    }
                }
            }
            if (category == "research" && name == "forensic_reconstruction") {
                discoveredLeads += 1
            }

            val semanticErrorInResponse = if (category == "provider" && name == "response") {
                val body = details.optString("response_body")
                if (body.isNotBlank()) extractEmbeddedErrorForReport(body) else null
            } else null

            if (semanticErrorInResponse != null) {
                issues += MonitorIssueSummary(
                    timestampMs = timestampMs,
                    level = "ERROR",
                    category = category,
                    event = name,
                    summary = "Embedded provider error: $semanticErrorInResponse",
                )
            } else if (isIssue(level, name)) {
                issues += MonitorIssueSummary(
                    timestampMs = timestampMs,
                    level = level,
                    category = category,
                    event = name,
                    summary = summarizeDetails(details),
                )
            }
        }
        val providerRequests = correlatedProviderRequestIds.size + uncorrelatedProviderRequests
        val settledUncorrelatedProviderRequests =
            uncorrelatedProviderRequests - uncorrelatedPendingProviderRequests
        val providerOutcomes = terminalProviderRequestIds.size + settledUncorrelatedProviderRequests
        discoveredLeads = maxOf(discoveredLeads, discoveredLeadUrls.size)
        val unfinishedProviderRequests = pendingProviderRequests.size + uncorrelatedPendingProviderRequests
        if (unfinishedProviderRequests > 0) {
            val latestPending = pendingProviderRequests.values.maxByOrNull { it.timestampMs }
            val detail = buildString {
                append(unfinishedProviderRequests)
                append(" provider request(s) had no recorded response or failure before the report boundary")
                latestPending?.operation?.takeIf(String::isNotBlank)?.let { append("; latest operation: $it") }
                latestPending?.endpoint?.takeIf(String::isNotBlank)?.let { append("; endpoint: $it") }
                append(". This is unfinished work, not a successful provider outcome.")
            }
            issues += MonitorIssueSummary(
                timestampMs = latestPending?.timestampMs ?: lastEventTimestamp,
                level = "WARN",
                category = "provider",
                event = "request_unfinished_at_report_boundary",
                summary = detail,
            )
        }
        return MonitorReportSummary(
            totalEvents = total,
            providerRequests = providerRequests,
            providerOutcomes = providerOutcomes,
            unfinishedProviderRequests = unfinishedProviderRequests,
            toolCalls = toolCalls,
            webSearches = webSearches,
            webFetches = webFetches,
            rabbitHoleIterations = rabbitHoleIterations,
            discoveredLeads = discoveredLeads,
            categoryCounts = categoryCounts,
            issueEvents = issues,
            provenanceIdentity = provenanceIdentity,
            firstMismatchEvent = firstMismatchEvent,
        )
    }

    private fun isIssue(level: String, event: String): Boolean {
        if (level.equals("ERROR", true) || level.equals("WARN", true)) return true
        val lowered = event.lowercase()
        return listOf("fail", "error", "timeout", "cancel", "recover", "stuck", "stall")
            .any(lowered::contains)
    }

    private fun summarizeDetails(details: JSONObject): String {
        val preferred = listOf(
            "embedded_error",
            "error_message",
            "message",
            "error",
            "reason",
            "status",
            "tool_name",
            "operation",
            "endpoint",
        )
        val value = preferred.asSequence()
            .mapNotNull { key -> details.optString(key).takeIf { it.isNotBlank() && it != "null" } }
            .firstOrNull()
            ?: details.toString()
        return value.replace(Regex("\\s+"), " ").take(700).ifBlank { "No additional detail." }
    }

    private fun formatTimestamp(value: Long): String = runCatching {
        Instant.ofEpochMilli(value).toString()
    }.getOrDefault(value.toString())

    private fun extractEmbeddedErrorForReport(body: String): String? = runCatching {
        val json = JSONObject(body)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val error = choice?.optJSONObject("error")
        val finishReason = choice?.optString("finish_reason")
        when {
            error != null -> error.optString("message").takeIf { it.isNotBlank() }
                ?: "Provider error code ${error.opt("code")}"
            finishReason == "error" -> "Provider finish reason: error"
            else -> null
        }
    }.getOrNull()

    private data class MonitorReportSummary(
        val totalEvents: Int,
        val providerRequests: Int,
        val providerOutcomes: Int,
        val unfinishedProviderRequests: Int,
        val toolCalls: Int,
        val webSearches: Int,
        val webFetches: Int,
        val rabbitHoleIterations: Int,
        val discoveredLeads: Int,
        val categoryCounts: Map<String, Int>,
        val issueEvents: List<MonitorIssueSummary>,
        val provenanceIdentity: String,
        val firstMismatchEvent: String?,
    )

    private data class MonitorIssueSummary(
        val timestampMs: Long,
        val level: String,
        val category: String,
        val event: String,
        val summary: String,
    )

    private data class PendingProviderRequest(
        val timestampMs: Long,
        val operation: String,
        val endpoint: String,
    )
}

private val MONITOR_SECRET_PATTERNS = listOf(
    Regex("(?i)\\bBearer\\s+[^\\s,;\\\"]+"),
    Regex("\\bsk-or(?:-v1)?-[A-Za-z0-9_-]{8,}\\b"),
    Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b"),
    Regex("(?i)(\\\"(?:[a-z0-9_-]+[_-])?(?:api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|session[_-]?token|client[_-]?secret|cookie|set[_-]?cookie|password|private[_-]?key)\\\"\\s*:\\s*)\\\"[^\\\"]*\\\""),
    Regex("(?i)(\\\"(?:latitude|longitude|altitude)\\\"\\s*:\\s*)-?\\d+(?:\\.\\d+)?"),
    Regex("(?i)\\b((?:[a-z0-9_-]+[_-])?(?:api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|session[_-]?token|client[_-]?secret|password|private[_-]?key|latitude|longitude|altitude))=([^&\\s]+)"),
)

private val MONITOR_SECRET_HEADER_PATTERN = Regex(
    "(?im)^(\\s*(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token)\\s*:\\s*).+$",
)

private val MONITOR_DATA_URL_PATTERN = Regex(
    "(?i)data:(?:image|audio|video|application)/[^,\\s\\\"]+;base64,[A-Za-z0-9+/=\\r\\n]+",
)
