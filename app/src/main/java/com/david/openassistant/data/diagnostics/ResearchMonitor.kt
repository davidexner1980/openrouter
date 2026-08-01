package com.david.openassistant.data.diagnostics

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ResearchMonitorStatus(
    val active: Boolean = false,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val eventCount: Int = 0,
    val traceBytes: Long = 0,
    val traceCapped: Boolean = false,
    val lastReportPath: String? = null,
    val lastReportBytes: Long = 0L,
    val lastReportSha256: String? = null,
    val lastReportCreatedAt: Long? = null,
    val applicationId: String? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val apkSha256: String? = null,
    val firstInstallTime: Long? = null,
    val targetClassification: String? = null,
    val apiLevel: Int? = null,
)

private data class ReportCapture(
    val sessionId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val wasActive: Boolean,
    val traceWasCapped: Boolean,
    val reportKind: String,
    val traceSnapshot: File,
    val report: File,
    val metadata: Map<String, Any?>,
)

/**
 * Opt-in, passive research flight recorder. Its SharedPreferences state is
 * process-independent, so WorkManager continues writing to the same session
 * when the UI is backgrounded or the app process is recreated.
 */
open class ResearchMonitor internal constructor(
    private val preferences: SharedPreferences,
    private val rootDirectory: File,
    private val cacheDir: File,
    private val contentResolver: ContentResolver? = null,
    private val packageManager: PackageManager? = null,
    private val packageName: String = "com.david.openassistant"
) {
    constructor(context: Context) : this(
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        rootDirectory = File(context.applicationContext.filesDir, ROOT_DIRECTORY_NAME),
        cacheDir = context.applicationContext.cacheDir,
        contentResolver = context.applicationContext.contentResolver,
        packageManager = context.applicationContext.packageManager,
        packageName = context.applicationContext.packageName
    )

    private val sessionDirectory = File(rootDirectory, SESSION_DIRECTORY_NAME)
    private val reportDirectory = File(rootDirectory, REPORT_DIRECTORY_NAME)

    open fun isActive(): Boolean = preferences.getBoolean(KEY_ACTIVE, false) &&
        preferences.getString(KEY_SESSION_ID, null) != null

    fun status(): ResearchMonitorStatus = synchronized(FILE_LOCK) {
        // Legacy automatic cleanup disabled in v1.8.23.4 (Hazard A mitigation).
        // User-requested public reports in Download/OpenAssistant/ now remain 
        // until the user deletes them.
        // removeLegacyPublicReportCopiesLocked()
        val sessionId = preferences.getString(KEY_SESSION_ID, null)
        val trace = sessionId?.let(::traceFile)
        val persistedEventCount = preferences.getInt(KEY_EVENT_COUNT, -1)
        ResearchMonitorStatus(
            active = preferences.getBoolean(KEY_ACTIVE, false) && sessionId != null,
            sessionId = sessionId,
            startedAt = preferences.getLong(KEY_STARTED_AT, 0L).takeIf { it > 0L },
            eventCount = if (persistedEventCount >= 0) {
                persistedEventCount
            } else {
                trace?.let(::countLines) ?: 0
            },
            traceBytes = trace?.takeIf(File::exists)?.length() ?: 0L,
            traceCapped = preferences.getBoolean(KEY_TRACE_CAPPED, false),
            lastReportPath = preferences.getString(KEY_LAST_REPORT_PATH, null)
                ?.takeIf { File(it).isFile },
            lastReportBytes = preferences.getLong(KEY_LAST_REPORT_BYTES, 0L),
            lastReportSha256 = preferences.getString(KEY_LAST_REPORT_SHA256, null),
            lastReportCreatedAt = preferences.getLong(KEY_LAST_REPORT_CREATED_AT, 0L)
                .takeIf { it > 0L },
            applicationId = preferences.getString(KEY_APP_ID, null),
            versionName = preferences.getString(KEY_VERSION_NAME, null),
            versionCode = preferences.getInt(KEY_VERSION_CODE, -1).takeIf { it != -1 },
            apkSha256 = preferences.getString(KEY_APK_SHA256, null),
            firstInstallTime = preferences.getLong(KEY_FIRST_INSTALL_TIME, 0L).takeIf { it > 0L },
            targetClassification = preferences.getString(KEY_TARGET_CLASSIFICATION, null),
            apiLevel = preferences.getInt(KEY_API_LEVEL, -1).takeIf { it != -1 },
        )
    }

    fun start(): ResearchMonitorStatus = synchronized(FILE_LOCK) {
        if (preferences.getBoolean(KEY_ACTIVE, false)) return@synchronized status()
        rootDirectory.mkdirs()
        sessionDirectory.mkdirs()
        reportDirectory.mkdirs()
        pruneDirectory(reportDirectory, MAX_RETAINED_REPORTS)
        val startedAt = System.currentTimeMillis()
        val sessionId = buildSessionId(startedAt)
        val trace = traceFile(sessionId)
        if (trace.exists()) trace.delete()
        
        val apkSha256 = computeApkSha256()
        val installTime = runCatching {
            packageManager?.getPackageInfo(packageName, 0)?.firstInstallTime
        }.getOrNull() ?: 0L
        val targetClassification = if (isEmulator()) "EMULATOR" else "PHYSICAL"
        
        val committed = runCatching {
            preferences.edit(commit = true) {
                putBoolean(KEY_ACTIVE, true)
                putString(KEY_SESSION_ID, sessionId)
                putLong(KEY_STARTED_AT, startedAt)
                putInt(KEY_EVENT_COUNT, 0)
                putBoolean(KEY_TRACE_CAPPED, false)
                putString(KEY_APP_ID, packageName)
                putString(KEY_VERSION_NAME, appVersion())
                putInt(KEY_VERSION_CODE, appVersionCode())
                putString(KEY_APK_SHA256, apkSha256)
                putLong(KEY_FIRST_INSTALL_TIME, installTime)
                putString(KEY_TARGET_CLASSIFICATION, targetClassification)
                putInt(KEY_API_LEVEL, Build.VERSION.SDK_INT)
            }
            true
        }.getOrDefault(false)
        check(committed) { "The research monitor could not persist its session state." }
        recordLocked(
            category = "monitor",
            event = "session_started",
            level = "INFO",
            correlationId = sessionId,
            fields = mapOf(
                "app_id" to packageName,
                "app_version" to appVersion(),
                "version_code" to appVersionCode(),
                "apk_sha256" to apkSha256,
                "install_timestamp" to installTime,
                "target_classification" to targetClassification,
                "device" to deviceLabel(),
                "android_version" to androidVersion(),
                "api_level" to Build.VERSION.SDK_INT,
                "capture_scope" to "app events, mission lifecycle, provider payloads/responses, searches, fetches, tool calls/results, failures, and recovery",
            ),
        )
        pruneDirectory(sessionDirectory, MAX_RETAINED_SESSIONS, trace)
        status()
    }

    fun createReport(stopAfterReport: Boolean): Pair<File, ResearchMonitorStatus> =
        synchronized(REPORT_LOCK) {
            var capture: ReportCapture? = null
            var pausedSessionId: String? = null
            try {
                capture = synchronized(FILE_LOCK) {
                    val sessionId = preferences.getString(KEY_SESSION_ID, null)
                        ?: throw IllegalStateException("Start the research monitor before creating a report.")
                    val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
                        .takeIf { it > 0L }
                        ?: throw IllegalStateException("The research monitor session has no valid start time.")
                    val wasActive = preferences.getBoolean(KEY_ACTIVE, false)
                    val finishedAt = System.currentTimeMillis()
                    if (wasActive) {
                        recordLocked(
                            category = "monitor",
                            event = if (stopAfterReport) "final_report_requested" else "snapshot_requested",
                            level = "INFO",
                            correlationId = sessionId,
                            fields = mapOf(
                                "event_count_before_report" to currentEventCount(sessionId),
                                "session_will_stop_after_successful_report" to stopAfterReport,
                            ),
                        )
                    }
                    if (wasActive && stopAfterReport) {
                        check(
                            runCatching {
                                preferences.edit(commit = true) {
                                    putBoolean(KEY_ACTIVE, false)
                                }
                                true
                            }.getOrDefault(false),
                        ) {
                            "The research monitor could not freeze the final report boundary."
                        }
                        pausedSessionId = sessionId
                    }
                    val traceSnapshot = File.createTempFile(
                        "openassistant-monitor-$sessionId-",
                        ".jsonl",
                        cacheDir,
                    )
                    val liveTrace = traceFile(sessionId)
                    if (liveTrace.isFile) {
                        liveTrace.copyTo(traceSnapshot, overwrite = true)
                    }
                    val suffix = if (stopAfterReport) "final" else "snapshot-$finishedAt"
                    ReportCapture(
                        sessionId = sessionId,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                        wasActive = wasActive,
                        traceWasCapped = preferences.getBoolean(KEY_TRACE_CAPPED, false),
                        reportKind = if (stopAfterReport) "final" else "snapshot",
                        traceSnapshot = traceSnapshot,
                        report = File(
                            reportDirectory,
                            "OpenAssistant-research-monitor-$sessionId-$suffix.md",
                        ),
                        metadata = mapOf(
                            "app_id" to preferences.getString(KEY_APP_ID, null),
                            "version_name" to preferences.getString(KEY_VERSION_NAME, null),
                            "version_code" to preferences.getInt(KEY_VERSION_CODE, -1).takeIf { it != -1 },
                            "apk_sha256" to preferences.getString(KEY_APK_SHA256, null),
                            "install_timestamp" to preferences.getLong(KEY_FIRST_INSTALL_TIME, 0L).takeIf { it > 0L },
                            "target_classification" to preferences.getString(KEY_TARGET_CLASSIFICATION, null),
                            "api_level" to preferences.getInt(KEY_API_LEVEL, -1).takeIf { it != -1 },
                        )
                    )
                }

                // Formatting and hashing can take time for multi-megabyte traces.
                // They operate on the immutable snapshot and never block event
                // recording or status refreshes.
                val immutableCapture = checkNotNull(capture)
                ResearchMonitorReportWriter.write(
                    traceFile = immutableCapture.traceSnapshot,
                    reportFile = immutableCapture.report,
                    metadata = ResearchMonitorReportMetadata(
                        sessionId = immutableCapture.sessionId,
                        startedAt = immutableCapture.startedAt,
                        finishedAt = immutableCapture.finishedAt,
                        appVersion = immutableCapture.metadata["version_name"]?.toString() ?: "unknown",
                        device = deviceLabel(),
                        androidVersion = androidVersion(),
                        monitoringStillActive = immutableCapture.wasActive && !stopAfterReport,
                        traceWasCapped = immutableCapture.traceWasCapped,
                        reportKind = immutableCapture.reportKind,
                        versionCode = immutableCapture.metadata["version_code"] as? Int ?: -1,
                        apkSha256 = immutableCapture.metadata["apk_sha256"]?.toString() ?: "unknown",
                        installationTimestamp = immutableCapture.metadata["install_timestamp"] as? Long ?: 0L,
                        targetClassification = immutableCapture.metadata["target_classification"]?.toString() ?: "unknown",
                        apiLevel = immutableCapture.metadata["api_level"] as? Int ?: -1,
                    ),
                )
                val report = immutableCapture.report
                check(report.isFile && report.length() > 0L) {
                    "The research monitor did not commit a non-empty report artifact."
                }
                check(
                    report.bufferedReader(StandardCharsets.UTF_8).use { it.readLine() } ==
                        "# OpenAssistant research monitor report",
                ) { "The research monitor committed an invalid report artifact." }
                val reportBytes = report.length()
                val reportSha256 = sha256(report)
                synchronized(FILE_LOCK) {
                    check(
                        runCatching {
                            preferences.edit(commit = true) {
                                putString(KEY_LAST_REPORT_PATH, report.absolutePath)
                                putLong(KEY_LAST_REPORT_BYTES, reportBytes)
                                putString(KEY_LAST_REPORT_SHA256, reportSha256)
                                putLong(KEY_LAST_REPORT_CREATED_AT, System.currentTimeMillis())
                            }
                            true
                        }.getOrDefault(false),
                    ) {
                        "The research monitor created the report but could not persist its final session state."
                    }
                }
                synchronized(FILE_LOCK) {
                    // A report is only a private handoff for the Android Studio
                    // receiver. Keep the newest committed handoff and remove any
                    // superseded snapshots/finals so the phone cannot accumulate
                    // duplicate monitor artifacts.
                    pruneDirectory(reportDirectory, MAX_RETAINED_REPORTS, report)
                }
                Log.i(
                    LOGCAT_TAG,
                    JSONObject()
                        .put("event", "research_monitor_report_ready")
                        .put("report_name", report.name)
                        .put("report_bytes", reportBytes)
                        .put("sha256", reportSha256)
                        .put("report_kind", immutableCapture.reportKind)
                        .put("device_staging", "app_private")
                        .toString(),
                )
                if (immutableCapture.wasActive && !stopAfterReport) {
                    record(
                        category = "monitor",
                        event = "snapshot_created",
                        level = "INFO",
                        correlationId = immutableCapture.sessionId,
                        fields = mapOf("report_name" to report.name, "report_bytes" to report.length()),
                    )
                }
                report to status()
            } catch (error: Throwable) {
                pausedSessionId?.let { sessionId ->
                    synchronized(FILE_LOCK) {
                        if (
                            preferences.getString(KEY_SESSION_ID, null) == sessionId &&
                            !preferences.getBoolean(KEY_ACTIVE, false)
                        ) {
                            preferences.edit(commit = true) {
                                putBoolean(KEY_ACTIVE, true)
                            }
                        }
                    }
                }
                throw error
            } finally {
                capture?.traceSnapshot?.delete()
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Versions through 1.8.8 published a redundant MediaStore Downloads copy.
     * The user-facing contract now has exactly one host handoff, so remove only
     * those precisely named legacy artifacts once and never publish them again.
     */
    private fun removeLegacyPublicReportCopiesLocked() {
        if (preferences.getBoolean(KEY_LEGACY_PUBLIC_CLEANUP_ATTEMPTED, false)) return
        var deletedCount = 0
        var failure: Throwable? = null
        val resolver = contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && resolver != null) {
            runCatching {
                val directory = "${Environment.DIRECTORY_DOWNLOADS}/OpenAssistant"
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                )
                val selection =
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(directory, "$directory/"),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn).orEmpty()
                        if (!LEGACY_PUBLIC_REPORT_NAME.matches(name)) continue
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn),
                        )
                        deletedCount += resolver.delete(uri, null, null).coerceAtLeast(0)
                    }
                }
            }.onFailure { error -> failure = error }
        }
        preferences.edit(commit = true) {
            putBoolean(KEY_LEGACY_PUBLIC_CLEANUP_ATTEMPTED, true)
        }
        if (deletedCount > 0) {
            Log.i(LOGCAT_TAG, "research_monitor_legacy_public_copies_removed count=$deletedCount")
        }
        failure?.let { error ->
            Log.w(LOGCAT_TAG, "research_monitor_legacy_public_cleanup_failed ${error.message.orEmpty()}")
        }
    }

    open fun record(
        category: String,
        event: String,
        level: String = "INFO",
        correlationId: String? = null,
        targetSessionId: String? = null,
        fields: Map<String, Any?> = emptyMap(),
    ) = synchronized(FILE_LOCK) {
        val activeSessionId = preferences.getString(KEY_SESSION_ID, null)
        
        // Strict version provenance check
        val incomingVersionName = fields["version_name"] as? String
        val incomingVersionCode = fields["version_code"] as? Int
        val sessionVersionName = preferences.getString(KEY_VERSION_NAME, null)
        val sessionVersionCode = preferences.getInt(KEY_VERSION_CODE, -1)
        
        val versionMismatch = (incomingVersionName != null && incomingVersionName != sessionVersionName) ||
            (incomingVersionCode != null && incomingVersionCode != sessionVersionCode)

        if (targetSessionId != null && targetSessionId != activeSessionId) {
            runCatching {
                recordToSpecificSessionLocked(
                    targetSessionId,
                    category,
                    "late_result_rejected",
                    "WARN",
                    correlationId,
                    fields + mapOf(
                        "original_event" to event,
                        "reason" to "The response belongs to an old or inactive session."
                    )
                )
            }
            return@synchronized
        }
        
        if (versionMismatch) {
            runCatching {
                recordLocked(
                    category = "monitor",
                    event = "mixed_version_event_rejected",
                    level = "ERROR",
                    correlationId = correlationId,
                    fields = fields + mapOf(
                        "original_event" to event,
                        "session_version" to sessionVersionName,
                        "event_version" to incomingVersionName,
                        "reason" to "Version identity differs from the immutable session provenance."
                    )
                )
            }
            return@synchronized
        }

        if (!preferences.getBoolean(KEY_ACTIVE, false)) return@synchronized
        runCatching { recordLocked(category, event, level, correlationId, fields) }
            .onFailure { preferences.edit { putBoolean(KEY_TRACE_CAPPED, true) } }
    }

    private fun recordToSpecificSessionLocked(
        sessionId: String,
        category: String,
        event: String,
        level: String,
        correlationId: String?,
        fields: Map<String, Any?>,
    ) {
        val trace = traceFile(sessionId)
        if (!trace.exists()) return
        if (trace.length() >= MAX_TRACE_BYTES) return

        val eventId = fields["event_id"] as? String ?: UUID.randomUUID().toString()
        if (isEventDeliveredLocked(trace, eventId)) return
        val payload = buildPayloadWithEventId(sessionId, category, event, level, correlationId, fields, eventId)
        trace.appendText(payload + "\n", StandardCharsets.UTF_8)
    }

    private fun recordLocked(
        category: String,
        event: String,
        level: String,
        correlationId: String?,
        fields: Map<String, Any?>,
    ) {
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return
        val trace = traceFile(sessionId)
        sessionDirectory.mkdirs()
        if (trace.exists() && trace.length() >= MAX_TRACE_BYTES) {
            preferences.edit { putBoolean(KEY_TRACE_CAPPED, true) }
            return
        }
        val eventId = fields["event_id"] as? String ?: UUID.randomUUID().toString()
        if (isEventDeliveredLocked(trace, eventId)) return

        val payload = buildPayloadWithEventId(sessionId, category, event, level, correlationId, fields, eventId)
        if (trace.length() + payload.toByteArray(StandardCharsets.UTF_8).size + 1 > MAX_TRACE_BYTES) {
            preferences.edit { putBoolean(KEY_TRACE_CAPPED, true) }
            return
        }
        trace.appendText(payload + "\n", StandardCharsets.UTF_8)
        val priorEventCount = preferences.getInt(KEY_EVENT_COUNT, -1)
        val updatedEventCount = if (priorEventCount >= 0) {
            priorEventCount + 1
        } else {
            countLines(trace)
        }
        preferences.edit { putInt(KEY_EVENT_COUNT, updatedEventCount) }
    }

    private fun isEventDeliveredLocked(trace: File, eventId: String): Boolean {
        if (!trace.exists()) return false
        return runCatching {
            trace.useLines { lines ->
                lines.any { line ->
                    if (line.contains(eventId)) {
                        runCatching { JSONObject(line).optString("event_id") == eventId }.getOrDefault(false)
                    } else false
                }
            }
        }.getOrDefault(false)
    }

    private fun buildPayloadWithEventId(
        sessionId: String,
        category: String,
        event: String,
        level: String,
        correlationId: String?,
        fields: Map<String, Any?>,
        eventId: String,
    ): String {
        val details = JSONObject()
        fields.entries
            .sortedBy { it.key }
            .take(MAX_FIELDS_PER_EVENT)
            .forEach { (rawKey, rawValue) ->
                val key = rawKey.trim().take(MAX_FIELD_KEY_CHARS)
                if (key.isBlank() || key == "event_id") return@forEach
                details.put(key, sanitizeValue(rawValue))
            }
        return JSONObject()
            .put("timestamp_ms", System.currentTimeMillis())
            .put("event_id", eventId)
            .put("session_id", sessionId)
            .put("level", level.trim().uppercase(Locale.US).take(12).ifBlank { "INFO" })
            .put("category", category.trim().lowercase(Locale.US).take(64).ifBlank { "unknown" })
            .put("event", event.trim().lowercase(Locale.US).take(96).ifBlank { "unknown" })
            .put("details", details)
            .apply {
                correlationId?.trim()?.takeIf(String::isNotBlank)?.let {
                    put("correlation_id", redactResearchMonitorText(it).take(256))
                }
            }
            .toString()
    }

    private fun sanitizeValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Number, is Boolean -> value
        is JSONObject -> {
            val sanitized = JSONObject()
            value.keys().forEach { key ->
                if (key == "reasoning" || key == "reasoning_details" || 
                    key == "latitude" || key == "longitude" || key == "altitude"
                ) {
                    sanitized.put(key, "[EXCLUDED FROM MONITOR]")
                } else {
                    sanitized.put(key, sanitizeValue(value.get(key)))
                }
            }
            sanitized
        }
        is JSONArray -> {
            val sanitized = JSONArray()
            for (i in 0 until value.length()) {
                sanitized.put(sanitizeValue(value.get(i)))
            }
            sanitized
        }
        else -> {
            val text = value.toString()
            val redacted = redactResearchMonitorText(text)
            if (redacted.length > MAX_FIELD_VALUE_CHARS) {
                JSONObject()
                    .put("truncated", true)
                    .put("original_redacted_characters", redacted.length)
                    .put("retained_characters", MAX_FIELD_VALUE_CHARS)
                    .put("content", redacted.take(MAX_FIELD_VALUE_CHARS))
            } else {
                redacted
            }
        }
    }

    private fun traceFile(sessionId: String): File = File(sessionDirectory, "$sessionId.jsonl")

    private fun countLines(file: File): Int {
        if (!file.isFile) return 0
        var count = 0
        file.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach { count += 1 } }
        return count
    }

    private fun currentEventCount(sessionId: String): Int {
        val persisted = preferences.getInt(KEY_EVENT_COUNT, -1)
        return if (persisted >= 0) persisted else countLines(traceFile(sessionId))
    }

    private fun pruneDirectory(
        directory: File,
        maximumFiles: Int,
        preferredFile: File? = null,
    ) {
        directory.mkdirs()
        val files = directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
        val preferredPath = preferredFile?.absoluteFile?.normalize()?.path
        files
            .sortedWith(
                compareByDescending<File> { it.absoluteFile.normalize().path == preferredPath }
                    .thenByDescending(File::lastModified),
            )
            .drop(maximumFiles)
            .forEach(File::delete)
    }

    private fun appVersion(): String = runCatching {
        packageManager?.getPackageInfo(packageName, 0)?.versionName.orEmpty()
    }.getOrDefault("unknown")

    private fun appVersionCode(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager?.getPackageInfo(packageName, 0)?.longVersionCode?.toInt() ?: -1
        } else {
            @Suppress("DEPRECATION")
            packageManager?.getPackageInfo(packageName, 0)?.versionCode ?: -1
        }
    }.getOrDefault(-1)

    private fun computeApkSha256(): String = runCatching {
        val info = packageManager?.getPackageInfo(packageName, 0)
        val apkFile = File(info?.applicationInfo?.sourceDir ?: return "unknown")
        sha256(apkFile)
    }.getOrDefault("unknown")

    private fun isEmulator(): Boolean =
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.PRODUCT.contains("sdk_google") ||
            Build.PRODUCT.contains("google_sdk") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("sdk_x86") ||
            Build.PRODUCT.contains("vbox86p") ||
            Build.PRODUCT.contains("emulator") ||
            Build.PRODUCT.contains("simulator")

    private fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { "unknown" }

    private fun androidVersion(): String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun buildSessionId(startedAt: Long): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        return "$timestamp-${UUID.randomUUID().toString().take(8)}"
    }

    private companion object {
        const val PREFERENCES_NAME = "openassistant_research_monitor"
        const val ROOT_DIRECTORY_NAME = "research_monitor"
        const val SESSION_DIRECTORY_NAME = "sessions"
        const val REPORT_DIRECTORY_NAME = "reports"
        const val KEY_ACTIVE = "active"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_EVENT_COUNT = "event_count"
        const val KEY_TRACE_CAPPED = "trace_capped"
        const val KEY_LAST_REPORT_PATH = "last_report_path"
        const val KEY_LAST_REPORT_BYTES = "last_report_bytes"
        const val KEY_LAST_REPORT_SHA256 = "last_report_sha256"
        const val KEY_LAST_REPORT_CREATED_AT = "last_report_created_at"
        const val KEY_APP_ID = "app_id"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_APK_SHA256 = "apk_sha256"
        const val KEY_FIRST_INSTALL_TIME = "first_install_time"
        const val KEY_TARGET_CLASSIFICATION = "target_classification"
        const val KEY_API_LEVEL = "api_level"
        const val KEY_LEGACY_PUBLIC_CLEANUP_ATTEMPTED = "legacy_public_cleanup_attempted_v1"
        const val MAX_FIELDS_PER_EVENT = 48
        const val MAX_FIELD_KEY_CHARS = 80
        const val MAX_FIELD_VALUE_CHARS = 16384
        const val MAX_TRACE_BYTES = 128L * 1024L * 1024L
        const val MAX_RETAINED_SESSIONS = 1
        const val MAX_RETAINED_REPORTS = 1
        const val LOGCAT_TAG = "OpenAssistant"
        val LEGACY_PUBLIC_REPORT_NAME = Regex(
            "OpenAssistant-research-monitor-[A-Za-z0-9._-]+-(?:final|snapshot-[0-9]+)(?: \\([0-9]+\\))?\\.md",
        )
        val FILE_LOCK = Any()
        val REPORT_LOCK = Any()
    }
}
