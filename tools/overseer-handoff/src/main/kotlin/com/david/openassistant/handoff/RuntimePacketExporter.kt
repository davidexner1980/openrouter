package com.david.openassistant.handoff

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import java.nio.charset.StandardCharsets

class RuntimePacketExporter(val projectRoot: File) {

    private val securityScanner = SecurityScanner()

    fun export(targetDir: File): JSONObject {
        val inputZip = File(projectRoot, "overseer-input/runtime/packet.zip")
        if (!inputZip.exists()) {
            targetDir.mkdirs()
            File(targetDir, "omitted-runtime-data.json").writeText(JSONObject().apply {
                put("reason", "Runtime input packet missing")
            }.toString(2))
            return JSONObject().put("status", "UNAVAILABLE")
        }

        targetDir.mkdirs()
        
        try {
            ZipFile(inputZip).use { zip ->
                // OH-016: ZIP safety checks
                if (zip.size() > 1000) return JSONObject().put("status", "INVALID_RUNTIME_PACKET").put("reason", "Excessive entry count")
                
                val manifestEntry = zip.getEntry("manifest.json") ?: return JSONObject().put("status", "INVALID_RUNTIME_PACKET").put("reason", "Missing manifest.json")
                val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().readText())
                
                // Verify hashes in manifest (basic check)
                val declaredHashes = manifest.optJSONObject("hashes") ?: JSONObject()
                declaredHashes.keys().forEach { fileName ->
                    if (fileName.contains("..") || fileName.startsWith("/") || fileName.contains(":")) {
                        return JSONObject().put("status", "INVALID_RUNTIME_PACKET").put("reason", "Unsafe path in manifest: $fileName")
                    }
                    val entry = zip.getEntry(fileName)
                    if (entry != null) {
                        val actualHash = sha256(zip.getInputStream(entry).readBytes())
                        if (actualHash != declaredHashes.getString(fileName)) {
                             return JSONObject().put("status", "INVALID_RUNTIME_PACKET").put("reason", "Hash mismatch for $fileName")
                        }
                    } else {
                        return JSONObject().put("status", "INVALID_RUNTIME_PACKET").put("reason", "Missing declared file: $fileName")
                    }
                }

                // Extract and redact important files
                val reportEntry = zip.getEntry("packet_verification_report.json")
                val report = if (reportEntry != null) {
                    JSONObject(zip.getInputStream(reportEntry).bufferedReader().readText())
                } else {
                    // Fallback for packets without a report but with a manifest
                    JSONObject().apply {
                        put("status", "EMPTY_RUNTIME_PACKET")
                        put("reason", "Verification report missing; treating as empty")
                    }
                }
                
                // OH-017: Repair logic errors
                val status = report.optString("status", "UNKNOWN")
                val sessionIds = if (manifest.has("session_identity")) listOf(manifest.getJSONObject("session_identity").optString("session_id")) else emptyList<String>()
                
                val hasEvents = zip.getEntry("runtime-events.jsonl")?.let { entry ->
                    zip.getInputStream(entry).use { it.read() != -1 }
                } ?: false
                
                val finalStatus = when {
                    status == "FINAL_SETTLED" && (sessionIds.isEmpty() || !hasEvents) -> "EMPTY_RUNTIME_PACKET"
                    status == "FINAL_SETTLED" -> {
                        // Check for unfinished work
                        val unfinishedProv = report.optInt("unfinished_provider_operations", 0)
                        val unfinishedTool = report.optInt("unfinished_tool_operations", 0)
                        if (unfinishedProv > 0 || unfinishedTool > 0) "FINAL_WITH_UNFINISHED_OPERATIONS" else "FINAL_SETTLED"
                    }
                    report.optBoolean("mixed_version_detected", false) -> "MIXED_VERSION"
                    else -> status
                }

                val extractedFiles = mutableListOf<String>()

                if (finalStatus == "EMPTY_RUNTIME_PACKET") {
                    report.put("monitor_session_ids", JSONArray())
                    report.put("unfinished_provider_operations", JSONObject.NULL)
                    report.put("unfinished_tool_operations", JSONObject.NULL)
                    report.put("mixed_version_detected", JSONObject.NULL)
                    report.put("raw_trace_sha256", JSONObject.NULL)
                    
                    File(targetDir, "status.json").writeText(JSONObject().apply {
                        put("status", "EMPTY_RUNTIME_PACKET")
                        put("reason", "No meaningful runtime activity recorded in this session")
                    }.toString(2))
                    
                    File(targetDir, "omitted-runtime-data.json").writeText(JSONObject().apply {
                        put("reason", "Runtime is honestly unavailable")
                    }.toString(2))
                } else {
                    // Extract and redact important files only if NOT empty
                    listOf(
                        "session-identity.json", "mission-summaries.json", "version-consistency.json",
                        "provider-accounting.json", "tool-accounting.json", "scheduler-summary.json",
                        "network-summary.json", "raw-runtime-identity.json", "redaction-report.json"
                    ).forEach { name ->
                        if (extractAndRedact(zip, name, targetDir)) {
                            extractedFiles.add(name)
                        }
                    }
                    
                    // OH-019: Move bulky raw monitor to EXTENDED or omit
                    val eventsEntry = zip.getEntry("runtime-events.jsonl")
                    if (eventsEntry != null) {
                        if (eventsEntry.size > 5 * 1024 * 1024) { // 5MB threshold
                            File(targetDir, "omitted-runtime-data.json").writeText(JSONObject().apply {
                                put("file", "runtime-events.jsonl")
                                put("size", eventsEntry.size)
                                put("reason", "Bulky raw evidence omitted from standard bundle")
                            }.toString(2))
                        } else {
                            if (extractAndRedact(zip, "runtime-events.jsonl", targetDir)) {
                                extractedFiles.add("runtime-events.jsonl")
                            }
                        }
                    }
                }

                report.put("status", finalStatus)
                val inputPacketId = manifest.optString("bundle_id")
                report.put("input_packet_id", inputPacketId)
                report.put("input_packet_zip_sha256", calculateFileSha256(inputZip))
                
                if (finalStatus == "EMPTY_RUNTIME_PACKET") {
                    report.put("runtime_packet_id", JSONObject.NULL)
                } else {
                    report.put("runtime_packet_id", inputPacketId)
                }

                // raw_trace_sha256 should come from manifest or be calculated from events file if present
                val rawTraceHash = manifest.optString("raw_trace_sha256", null) ?: (if (hasEvents) sha256(zip.getInputStream(zip.getEntry("runtime-events.jsonl")).readBytes()) else null)
                report.put("raw_trace_sha256", rawTraceHash ?: JSONObject.NULL)
                
                File(targetDir, "monitor-summary.md").writeText("# Monitor Summary\n\nRuntime status: $finalStatus")
                File(targetDir, "runtime-index.json").writeText(JSONObject().apply {
                    put("input_packet_id", inputPacketId)
                    put("files", JSONArray(extractedFiles))
                }.toString(2))
                
                // Save imported report
                File(targetDir, "packet_verification_report.json").writeText(report.toString(2))
                
                return report
            }
        } catch (e: Exception) {
            return JSONObject().put("status", "INVALID_RUNTIME_PACKET").put("reason", e.message)
        }
    }

    private fun extractAndRedact(zip: ZipFile, name: String, targetDir: File): Boolean {
        val entry = zip.getEntry(name) ?: return false
        val content = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
        
        // Redact evidence
        val (redacted, _) = securityScanner.redact(content, ScannerScope.RUNTIME_PROVIDER_DATA)
        File(targetDir, name).writeText(redacted)
        return true
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
    
    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
