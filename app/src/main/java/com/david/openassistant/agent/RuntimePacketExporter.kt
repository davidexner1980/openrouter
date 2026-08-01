package com.david.openassistant.agent

import android.content.Context
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.redactResearchMonitorText
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports app-private runtime evidence into a redacted Overseer Runtime Packet.
 *
 * The packet is diagnostic evidence, not an execution authority: it redacts
 * monitor logs, records settlement/mission status, and bundles hashes so a
 * reviewer can validate what happened without exposing secrets.
 */
class RuntimePacketExporter(private val context: Context) {

    private val researchMonitor = ResearchMonitor(context)
    private val store = AgentStore(context)

    data class ExportResult(
        val zipData: ByteArray,
        val fileName: String,
        val manifest: JSONObject
    )

    fun export(): ExportResult {
        val bundleId = UUID.randomUUID().toString()
        val timestamp = Instant.now().toString()
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        
        val status = researchMonitor.status()
        val snapshot = store.loadSnapshot()
        val goals = snapshot.goals
        
        val filesToHash = mutableMapOf<String, String>()

        val providerSettled = ProviderRequestLedger.isSettled()
        val missionTerminal = goals.isNotEmpty() && goals.all {
            it.status.isFinalTerminalStatus() || it.status in setOf(AgentGoalStatus.FAILED, AgentGoalStatus.BLOCKED)
        }
        
        // OH-004: Determine actual runtime packet status based on settlement and mission state
        val appStatus = when {
            status.sessionId == null -> "UNAVAILABLE"
            status.eventCount == 0 -> "EMPTY_RUNTIME_PACKET"
            status.active -> "SNAPSHOT_ACTIVE"
            missionTerminal && providerSettled -> "FINAL_COMPLETE"
            missionTerminal -> "FINAL_MISSION_TERMINAL_UNSETTLED"
            providerSettled -> "FINAL_OPERATIONS_SETTLED_MISSION_ACTIVE"
            else -> "FINAL_ACTIVE_UNSETTLED"
        }

        val monitorData = collectMonitorData(status.sessionId)

        // 1. Manifest
        val manifest = JSONObject().apply {
            put("schema_version", 3)
            put("bundle_id", bundleId)
            put("created_at_utc", timestamp)
            put("app_status", appStatus)
            put("monitor_finalized", !status.active)
            put("provider_operations_settled", providerSettled)
            put("tool_operations_settled", true) // AutonomousToolRuntime has no persistent outbox yet
            put("mission_terminal", missionTerminal)
            put("mission_status", goals.firstOrNull()?.status?.name ?: "UNKNOWN")
            put("packet_integrity_verified", monitorData.malformedLines == 0)
            put("project", JSONObject().apply {
                put("application_id", status.applicationId ?: "com.david.openassistant")
                put("version_name", status.versionName ?: "unknown")
                put("version_code", status.versionCode ?: -1)
                put("apk_sha256", status.apkSha256 ?: "unknown")
            })
            put("session_identity", JSONObject().apply {
                put("session_id", status.sessionId ?: JSONObject.NULL)
                put("started_at", status.startedAt ?: JSONObject.NULL)
            })
        }

        // 2. Mission Summaries
        val missionSummaries = JSONArray()
        goals.forEach { goal ->
            missionSummaries.put(summarizeGoal(goal))
        }
        addToZip(zos, "mission-summaries.json", missionSummaries.toString(2), filesToHash)

        // 3. Version Consistency
        val versionConsistency = JSONObject().apply {
            put("version_name", status.versionName)
            put("version_code", status.versionCode)
            put("apk_sha256", status.apkSha256)
            // OH-013: Do not automatically claim mixed-version based on active status alone
            put("mixed_version_detected", JSONObject.NULL) 
        }
        addToZip(zos, "version-consistency.json", versionConsistency.toString(2), filesToHash)

        // 4. Accounting (Provider & Tool)
        val providerAccounting = calculateProviderAccounting(goals)
        addToZip(zos, "provider-accounting.json", providerAccounting.toString(2), filesToHash)
        
        val toolAccounting = calculateToolAccounting(monitorData.toolEvents)
        addToZip(zos, "tool-accounting.json", toolAccounting.toString(2), filesToHash)

        // 5. Scheduler & Network Summaries
        val schedulerSummary = calculateSchedulerSummary(goals)
        addToZip(zos, "scheduler-summary.json", schedulerSummary.toString(2), filesToHash)
        
        val networkSummary = calculateNetworkSummary(goals)
        addToZip(zos, "network-summary.json", networkSummary.toString(2), filesToHash)

        // 5b. Public Export Ledger
        val publicExportLedger = collectPublicExportLedger()
        addToZip(zos, "public-export-ledger.jsonl", publicExportLedger, filesToHash)

        // 6. Monitor Data (Events & Tail)
        addToZip(zos, "runtime-events.jsonl", monitorData.eventsTail, filesToHash)
        
        // 7. Raw Runtime Identity
        val rawIdentity = JSONObject().apply {
            put("session_id", status.sessionId)
            put("total_event_count", status.eventCount)
            put("trace_byte_size", status.traceBytes)
            put("trace_sha256", monitorData.fullTraceSha256)
            put("tail_event_count", monitorData.tailEventCount)
            put("unfinished_provider_operations", ProviderRequestLedger.activeRequestIds().size)
        }
        addToZip(zos, "raw-runtime-identity.json", rawIdentity.toString(2), filesToHash)

        // 8. Redaction Report
        val redactionReport = JSONObject().apply {
            put("approx_secrets_redacted", monitorData.secretsRedacted)
            put("hidden_reasoning_removed", monitorData.reasoningRedacted)
        }
        addToZip(zos, "redaction-report.json", redactionReport.toString(2), filesToHash)

        // 9. Packet Verification Report (Self-verification)
        val verificationReport = JSONObject().apply {
            val isBrokenEmpty = appStatus == "EMPTY_RUNTIME_PACKET" && status.sessionId != null
            put("status", if (isBrokenEmpty || monitorData.malformedLines > 0) "INVALID_RUNTIME_PACKET" else appStatus)
            put("reason", when {
                isBrokenEmpty -> "Session exists but has no events"
                monitorData.malformedLines > 0 -> "Trace contains ${monitorData.malformedLines} malformed JSONL lines"
                else -> JSONObject.NULL
            })
            put("total_lines", monitorData.totalLines)
            put("parsed_lines", monitorData.parsedLines)
            put("malformed_lines", monitorData.malformedLines)
        }
        addToZip(zos, "packet_verification_report.json", verificationReport.toString(2), filesToHash)

        // 10. Finalize Manifest with Hashes
        manifest.put("hashes", JSONObject(filesToHash))
        addToZip(zos, "manifest.json", manifest.toString(2), null)

        zos.close()
        
        val fileName = "OpenAssistant-Runtime-Packet-${status.sessionId ?: "orphan"}-${System.currentTimeMillis()}.zip"
        return ExportResult(baos.toByteArray(), fileName, manifest)
    }

    private fun summarizeGoal(goal: AgentGoal): JSONObject = JSONObject().apply {
        put("goal_id", goal.id)
        put("status", goal.status.name)
        put("title", goal.title)
        put("user_request", goal.userRequest)
        put("total_tokens", goal.totalTokens)
        put("total_cost_usd", goal.totalCostUsd)
        put("unfinished_operations", goal.requestAttempts.count { it.exchangeOutcome == ExchangeOutcome.ACTIVE })
    }

    private fun calculateProviderAccounting(goals: List<AgentGoal>): JSONObject {
        var totalRequests = 0
        var totalOutcomes = 0
        var totalTokens = 0
        var totalCost = 0.0
        
        goals.forEach { goal ->
            totalRequests += goal.requestAttempts.size
            totalOutcomes += goal.requestAttempts.count { it.exchangeOutcome != ExchangeOutcome.ACTIVE }
            totalTokens += goal.totalTokens
            totalCost += goal.totalCostUsd
        }
        
        return JSONObject().apply {
            put("total_requests", totalRequests)
            put("total_outcomes", totalOutcomes)
            put("total_tokens", totalTokens)
            put("total_cost_usd", totalCost)
            put("unfinished_operations", totalRequests - totalOutcomes)
        }
    }

    private fun calculateToolAccounting(toolEvents: List<JSONObject>): JSONObject {
        val started = toolEvents.filter { it.optString("event") == "call_started" }
        val completed = toolEvents.filter { it.optString("event") == "call_completed" }
        val failed = toolEvents.filter { it.optString("event") == "call_failed" }
        
        return JSONObject().apply {
            put("total_calls", started.size)
            put("successful_calls", completed.size)
            put("failed_calls", failed.size)
            put("unfinished_calls", started.size - completed.size - failed.size)
        }
    }

    private fun calculateSchedulerSummary(goals: List<AgentGoal>): JSONObject {
        val statusCounts = goals.groupingBy { it.status.name }.eachCount()
        return JSONObject().apply {
            put("total_goals", goals.size)
            put("status_distribution", JSONObject(statusCounts))
        }
    }

    private fun calculateNetworkSummary(goals: List<AgentGoal>): JSONObject {
        var totalWaitCount = 0
        var goalsCurrentlyWaiting = 0
        var totalFingerprintAuthorizations = 0
        
        goals.forEach { goal ->
            totalWaitCount += goal.networkRetryCount
            if (goal.status == AgentGoalStatus.WAITING_FOR_NETWORK) {
                goalsCurrentlyWaiting++
            }
            goal.tasks.forEach { task ->
                if (task.retryAuthorizedFingerprint != null) {
                    totalFingerprintAuthorizations++
                }
            }
        }
        return JSONObject().apply {
            put("total_network_retries", totalWaitCount)
            put("goals_waiting_for_network", goalsCurrentlyWaiting)
            put("fingerprint_authorizations_active", totalFingerprintAuthorizations)
        }
    }

    private data class MonitorData(
        val eventsTail: String,
        val tailEventCount: Int,
        val fullTraceSha256: String,
        val secretsRedacted: Int,
        val reasoningRedacted: Int,
        val toolEvents: List<JSONObject>,
        val totalLines: Int,
        val parsedLines: Int,
        val malformedLines: Int
    )

    private fun collectMonitorData(sessionId: String?): MonitorData {
        if (sessionId == null) return MonitorData("", 0, "", 0, 0, emptyList(), 0, 0, 0)
        
        val sessionDir = File(context.filesDir, "research_monitor/sessions")
        val traceFile = File(sessionDir, "$sessionId.jsonl")
        if (!traceFile.exists()) return MonitorData("", 0, "", 0, 0, emptyList(), 0, 0, 0)

        val digest = MessageDigest.getInstance("SHA-256")
        val toolEvents = mutableListOf<JSONObject>()
        
        var totalLines = 0
        var parsedLines = 0
        var malformedLines = 0
        
        // Read the entire file for tool events and hash (streaming)
        traceFile.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    totalLines++
                    digest.update(line.toByteArray(StandardCharsets.UTF_8))
                    digest.update("\n".toByteArray(StandardCharsets.UTF_8))
                    
                    try {
                        val json = JSONObject(line)
                        parsedLines++
                        if (json.optString("category") == "tool") {
                            toolEvents.add(json)
                        }
                    } catch (e: Exception) {
                        malformedLines++
                    }
                }
                line = reader.readLine()
            }
        }
        val fullSha256 = digest.digest().joinToString("") { "%02x".format(it) }

        // Read tail with bounded memory
        val tailLimit = 500
        val lastLines = java.util.ArrayDeque<String>(tailLimit)
        traceFile.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    if (lastLines.size >= tailLimit) {
                        lastLines.pollFirst()
                    }
                    lastLines.addLast(line)
                }
                line = reader.readLine()
            }
        }

        var secretsCount = 0
        var reasoningCount = 0
        val redactedLines = lastLines.map { line ->
            // Use redactResearchMonitorText for secrets
            val redacted = redactResearchMonitorText(line)
            if (redacted != line) secretsCount++
            
            // Check for reasoning fields (simplified)
            if (line.contains("\"reasoning\"") || line.contains("\"reasoning_details\"")) {
                reasoningCount++
            }
            redacted
        }

        return MonitorData(
            eventsTail = redactedLines.joinToString("\n"),
            tailEventCount = redactedLines.size,
            fullTraceSha256 = fullSha256,
            secretsRedacted = secretsCount,
            reasoningRedacted = reasoningCount,
            toolEvents = toolEvents,
            totalLines = totalLines,
            parsedLines = parsedLines,
            malformedLines = malformedLines
        )
    }

    private fun collectPublicExportLedger(): String {
        val ledgerFile = File(context.filesDir, "research_monitor/public_export_ledger.jsonl")
        if (!ledgerFile.exists()) return ""
        return runCatching { ledgerFile.readText(StandardCharsets.UTF_8) }.getOrDefault("")
    }

    private fun addToZip(zos: ZipOutputStream, name: String, content: String, hashStore: MutableMap<String, String>?) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
        
        if (hashStore != null) {
            hashStore[name] = sha256(bytes)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
