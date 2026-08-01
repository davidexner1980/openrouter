package com.david.openassistant.data.diagnostics

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class ExportStatus {
    NOT_REQUESTED,
    EXPORTING,
    EXPORTED,
    PERMISSION_REQUIRED,
    FAILED_RETRYABLE,
    FAILED_FINAL
}

enum class ReportKind {
    SNAPSHOT,
    FINAL
}

data class ExportRequest(
    val canonicalFile: File,
    val reportKind: ReportKind,
    val canonicalBytes: Long,
    val canonicalSha256: String,
    val sessionId: String,
    val createdAt: Long
)

data class ExportResult(
    val status: ExportStatus,
    val reportKind: ReportKind,
    val displayName: String? = null,
    val destinationKind: String? = null,
    val contentUri: String? = null, // URI or safe reference
    val relativePath: String? = null,
    val bytesWritten: Long = 0L,
    val verifiedSha256: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val failureCategory: String? = null,
    val failureMessage: String? = null,
    val retryable: Boolean = false
)

interface ResearchMonitorDownloadsStore {
    fun export(request: ExportRequest): ExportResult
    fun deleteIncomplete(displayName: String)
}

class MediaStoreDownloadsStore(private val context: Context) : ResearchMonitorDownloadsStore {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun export(request: ExportRequest): ExportResult {
        val resolver = context.contentResolver
        val displayName = buildDisplayName(request)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/OpenAssistant"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportResult(
                status = ExportStatus.FAILED_RETRYABLE,
                reportKind = request.reportKind,
                failureCategory = "INSERT_FAILED",
                failureMessage = "Could not insert MediaStore record.",
                retryable = true
            )

        try {
            resolver.openOutputStream(uri)?.use { output ->
                request.canonicalFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            } ?: throw IllegalStateException("Could not open output stream for URI: $uri")

            // Verify
            val verified = verifyExport(resolver, uri, request.canonicalSha256, request.canonicalBytes)
            if (!verified.success) {
                resolver.delete(uri, null, null)
                return ExportResult(
                    status = ExportStatus.FAILED_RETRYABLE,
                    reportKind = request.reportKind,
                    failureCategory = verified.category,
                    failureMessage = verified.message,
                    retryable = true
                )
            }

            // Query back the actual display name (MediaStore might have added a suffix)
            val actualDisplayName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else displayName
            } ?: displayName

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return ExportResult(
                status = ExportStatus.EXPORTED,
                reportKind = request.reportKind,
                displayName = actualDisplayName,
                destinationKind = "MediaStore",
                contentUri = uri.toString(),
                relativePath = "Download/OpenAssistant",
                bytesWritten = request.canonicalBytes,
                verifiedSha256 = request.canonicalSha256
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return ExportResult(
                status = ExportStatus.FAILED_RETRYABLE,
                reportKind = request.reportKind,
                failureCategory = "WRITE_FAILED",
                failureMessage = e.message ?: "Unknown write error",
                retryable = true
            )
        }
    }

    override fun deleteIncomplete(displayName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.IS_PENDING} = 1"
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, arrayOf(displayName))
        }
    }
}

class LegacyDownloadsStore(private val context: Context) : ResearchMonitorDownloadsStore {
    override fun export(request: ExportRequest): ExportResult {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val openAssistantDir = File(downloadsDir, "OpenAssistant")
        if (!openAssistantDir.exists() && !openAssistantDir.mkdirs()) {
            return ExportResult(
                status = ExportStatus.FAILED_RETRYABLE,
                reportKind = request.reportKind,
                failureCategory = "DIR_CREATION_FAILED",
                failureMessage = "Could not create Download/OpenAssistant directory.",
                retryable = true
            )
        }

        val baseName = buildBaseName(request)
        var displayName = "$baseName.md"
        var targetFile = File(openAssistantDir, displayName)
        var collisionIndex = 2
        while (targetFile.exists()) {
            displayName = "$baseName-$collisionIndex.md"
            targetFile = File(openAssistantDir, displayName)
            collisionIndex++
        }

        val tempFile = File(openAssistantDir, ".$displayName.${UUID.randomUUID()}.tmp")

        try {
            request.canonicalFile.inputStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    try {
                        output.getFD().sync()
                    } catch (e: Exception) {
                        // fsync not supported on all filesystems
                    }
                }
            }

            // Verify
            val actualBytes = tempFile.length()
            val actualSha = computeSha256(tempFile)

            if (actualBytes != request.canonicalBytes) {
                tempFile.delete()
                return ExportResult(
                    status = ExportStatus.FAILED_RETRYABLE,
                    reportKind = request.reportKind,
                    failureCategory = "BYTE_COUNT_MISMATCH",
                    failureMessage = "Expected ${request.canonicalBytes} bytes, wrote $actualBytes.",
                    retryable = true
                )
            }

            if (actualSha != request.canonicalSha256) {
                tempFile.delete()
                return ExportResult(
                    status = ExportStatus.FAILED_RETRYABLE,
                    reportKind = request.reportKind,
                    failureCategory = "SHA256_MISMATCH",
                    failureMessage = "Public copy SHA-256 does not match canonical report.",
                    retryable = true
                )
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                return ExportResult(
                    status = ExportStatus.FAILED_RETRYABLE,
                    reportKind = request.reportKind,
                    failureCategory = "RENAME_FAILED",
                    failureMessage = "Could not rename temporary file to final filename.",
                    retryable = true
                )
            }

            return ExportResult(
                status = ExportStatus.EXPORTED,
                reportKind = request.reportKind,
                displayName = displayName,
                destinationKind = "LegacyFile",
                contentUri = targetFile.absolutePath, // Opaque safe reference in VM
                relativePath = "Download/OpenAssistant",
                bytesWritten = request.canonicalBytes,
                verifiedSha256 = request.canonicalSha256
            )
        } catch (e: Exception) {
            tempFile.delete()
            return ExportResult(
                status = ExportStatus.FAILED_RETRYABLE,
                reportKind = request.reportKind,
                failureCategory = "WRITE_FAILED",
                failureMessage = e.message ?: "Unknown write error",
                retryable = true
            )
        }
    }

    override fun deleteIncomplete(displayName: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val openAssistantDir = File(downloadsDir, "OpenAssistant")
        openAssistantDir.listFiles { _, name -> 
            name.startsWith(".$displayName") && name.endsWith(".tmp")
        }?.forEach { it.delete() }
    }
}

class ResearchMonitorDownloadsExporter(private val context: Context) {
    private val mediaStoreStore = MediaStoreDownloadsStore(context)
    private val legacyStore = LegacyDownloadsStore(context)

    fun export(request: ExportRequest): ExportResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreStore.export(request)
        } else {
            legacyStore.export(request)
        }
    }
}

private fun buildBaseName(request: ExportRequest): String {
    val kind = request.reportKind.name.lowercase(Locale.US)
    return "OpenAssistant-research-monitor-${request.sessionId}-$kind"
}

private fun buildDisplayName(request: ExportRequest): String {
    return "${buildBaseName(request)}.md"
}

private data class VerificationResult(
    val success: Boolean,
    val category: String? = null,
    val message: String? = null
)

private fun verifyExport(
    resolver: ContentResolver,
    uri: Uri,
    expectedSha: String,
    expectedBytes: Long
): VerificationResult {
    try {
        resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalRead = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    totalRead += read
                }
            }
            if (totalRead != expectedBytes) {
                return VerificationResult(false, "BYTE_COUNT_MISMATCH", "Expected $expectedBytes bytes, found $totalRead.")
            }
            val actualSha = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (actualSha != expectedSha) {
                return VerificationResult(false, "SHA256_MISMATCH", "Public copy SHA-256 does not match canonical report.")
            }
            return VerificationResult(true)
        } ?: return VerificationResult(false, "OPEN_FAILED", "Could not reopen URI for verification.")
    } catch (e: Exception) {
        return VerificationResult(false, "VERIFICATION_ERROR", e.message ?: "Unknown verification error")
    }
}

private fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
