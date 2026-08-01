package com.david.openassistant.data.diagnostics

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PublicExportMetadata(
    val operationId: String,
    val sessionId: String,
    val reportKind: ReportKind,
    val canonicalSha256: String,
    val canonicalBytes: Long,
    val createdAt: Long,
    val exportStatus: ExportStatus,
    val displayName: String? = null,
    val contentUri: String? = null,
    val failureCategory: String? = null,
    val failureMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

fun PublicExportMetadata.toExportResult(): ExportResult {
    return ExportResult(
        status = exportStatus,
        reportKind = reportKind,
        displayName = displayName,
        destinationKind = if (contentUri?.startsWith("content://") == true) "MediaStore" else "LegacyFile",
        contentUri = contentUri,
        relativePath = "Download/OpenAssistant",
        bytesWritten = canonicalBytes,
        verifiedSha256 = canonicalSha256,
        timestamp = timestamp,
        failureCategory = failureCategory,
        failureMessage = failureMessage,
        retryable = exportStatus != ExportStatus.FAILED_FINAL
    )
}

class PublicExportManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("public_export_manager", Context.MODE_PRIVATE)
    private val rootDirectory = File(context.filesDir, "research_monitor")
    private val outboxDirectory = File(rootDirectory, "public_export_outbox")
    private val ledgerFile = File(rootDirectory, "public_export_ledger.jsonl")
    private val exporter = ResearchMonitorDownloadsExporter(context)

    init {
        outboxDirectory.mkdirs()
    }

    fun loadLastExportMetadata(): PublicExportMetadata? {
        val jsonString = preferences.getString("last_export_metadata", null) ?: return null
        return runCatching {
            val json = JSONObject(jsonString)
            PublicExportMetadata(
                operationId = json.getString("operation_id"),
                sessionId = json.getString("session_id"),
                reportKind = ReportKind.valueOf(json.getString("report_kind")),
                canonicalSha256 = json.getString("canonical_sha256"),
                canonicalBytes = json.getLong("canonical_bytes"),
                createdAt = json.getLong("created_at"),
                exportStatus = ExportStatus.valueOf(json.getString("export_status")),
                displayName = json.optString("display_name").takeIf { it.isNotBlank() },
                contentUri = json.optString("content_uri").takeIf { it.isNotBlank() },
                failureCategory = json.optString("failure_category").takeIf { it.isNotBlank() },
                failureMessage = json.optString("failure_message").takeIf { it.isNotBlank() },
                timestamp = json.getLong("timestamp")
            )
        }.getOrNull()
    }

    private fun saveMetadata(metadata: PublicExportMetadata) {
        val json = JSONObject()
            .put("operation_id", metadata.operationId)
            .put("session_id", metadata.sessionId)
            .put("report_kind", metadata.reportKind.name)
            .put("canonical_sha256", metadata.canonicalSha256)
            .put("canonical_bytes", metadata.canonicalBytes)
            .put("created_at", metadata.createdAt)
            .put("export_status", metadata.exportStatus.name)
            .put("display_name", metadata.displayName)
            .put("content_uri", metadata.contentUri)
            .put("failure_category", metadata.failureCategory)
            .put("failure_message", metadata.failureMessage)
            .put("timestamp", metadata.timestamp)
        
        preferences.edit { putString("last_export_metadata", json.toString()) }
    }

    fun prepareExport(
        sessionId: String,
        reportFile: File,
        reportKind: ReportKind,
        sha256: String,
        bytes: Long
    ): PublicExportMetadata {
        val operationId = UUID.randomUUID().toString()
        val outboxFile = File(outboxDirectory, "$operationId.md")
        
        reportFile.copyTo(outboxFile, overwrite = true)
        
        val metadata = PublicExportMetadata(
            operationId = operationId,
            sessionId = sessionId,
            reportKind = reportKind,
            canonicalSha256 = sha256,
            canonicalBytes = bytes,
            createdAt = System.currentTimeMillis(),
            exportStatus = ExportStatus.EXPORTING
        )
        
        saveMetadata(metadata)
        recordEvent("research_monitor_public_export_started", metadata)
        pruneOutbox()
        return metadata
    }

    fun executeExport(metadata: PublicExportMetadata): ExportResult {
        val outboxFile = File(outboxDirectory, "${metadata.operationId}.md")
        if (!outboxFile.exists()) {
            return ExportResult(
                status = ExportStatus.FAILED_FINAL,
                reportKind = metadata.reportKind,
                failureCategory = "OUTBOX_MISSING",
                failureMessage = "Source file for retry is missing from outbox."
            )
        }

        // Verify outbox integrity before exporting
        if (outboxFile.length() != metadata.canonicalBytes) {
            return ExportResult(
                status = ExportStatus.FAILED_FINAL,
                reportKind = metadata.reportKind,
                failureCategory = "OUTBOX_CORRUPT_SIZE",
                failureMessage = "Outbox file size mismatch."
            )
        }

        val request = ExportRequest(
            canonicalFile = outboxFile,
            reportKind = metadata.reportKind,
            canonicalBytes = metadata.canonicalBytes,
            canonicalSha256 = metadata.canonicalSha256,
            sessionId = metadata.sessionId,
            createdAt = metadata.createdAt
        )

        val result = exporter.export(request)
        
        val updatedMetadata = metadata.copy(
            exportStatus = result.status,
            displayName = result.displayName,
            contentUri = result.contentUri,
            failureCategory = result.failureCategory,
            failureMessage = result.failureMessage,
            timestamp = result.timestamp
        )
        saveMetadata(updatedMetadata)

        val eventType = when (result.status) {
            ExportStatus.EXPORTED -> "research_monitor_public_export_completed"
            else -> "research_monitor_public_export_failed"
        }
        
        recordEvent(eventType, updatedMetadata, result)
        
        if (result.status == ExportStatus.EXPORTED) {
            outboxFile.delete()
        }
        
        return result
    }

    fun getOutboxFile(operationId: String): File = File(outboxDirectory, "$operationId.md")

    fun validateExportedItem(metadata: PublicExportMetadata): Boolean {
        if (metadata.exportStatus != ExportStatus.EXPORTED) return false
        val uriString = metadata.contentUri ?: return false
        
        return if (uriString.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(uriString.toUri())?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            File(uriString).exists()
        }
    }

    private fun recordEvent(event: String, metadata: PublicExportMetadata, result: ExportResult? = null) {
        val json = JSONObject()
            .put("timestamp_ms", System.currentTimeMillis())
            .put("event", event)
            .put("operation_id", metadata.operationId)
            .put("session_id", metadata.sessionId)
            .put("report_kind", metadata.reportKind.name)
            .put("canonical_sha256", metadata.canonicalSha256)
            .put("canonical_bytes", metadata.canonicalBytes)
        
        result?.let {
            json.put("status", it.status.name)
                .put("display_name", it.displayName)
                .put("failure_category", it.failureCategory)
                .put("failure_message", it.failureMessage)
        }

        try {
            ledgerFile.appendText(json.toString() + "\n", StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e("PublicExportManager", "Failed to write to ledger", e)
        }
    }

    private fun pruneOutbox() {
        val lastExport = loadLastExportMetadata()
        val protectedId = lastExport?.operationId
        
        val files = outboxDirectory.listFiles()?.filter { 
            it.isFile && it.name.endsWith(".md") && it.nameWithoutExtension != protectedId 
        } ?: return
        
        if (files.size > 10) {
            files.sortedBy { it.lastModified() }
                .take(files.size - 10)
                .forEach { it.delete() }
        }
    }
}
