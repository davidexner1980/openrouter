package com.david.openassistant.data.diagnostics

import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.json.JSONObject as RealJSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResearchMonitorProvenanceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var cacheDir: File

    class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val tempValues = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { tempValues[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { tempValues.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { tempValues.clear() }
            override fun commit(): Boolean {
                prefs.values.putAll(tempValues)
                return true
            }
            override fun apply() { commit() }
        }
    }

    @Before
    fun setup() {
        rootDir = tempFolder.newFolder("monitor")
        cacheDir = tempFolder.newFolder("cache")
    }

    @Test
    fun `test record rejects mismatched version`() {
        val prefs = FakeSharedPreferences()
        val monitor = ResearchMonitor(
            preferences = prefs,
            rootDirectory = rootDir,
            cacheDir = cacheDir
        )
        
        prefs.edit()
            .putString("session_id", "session-1")
            .putBoolean("active", true)
            .putString("version_name", "1.8.33")
            .putInt("version_code", 53)
            .commit()

        val traceFile = File(File(rootDir, "sessions"), "session-1.jsonl")
        traceFile.parentFile?.mkdirs()
        traceFile.createNewFile()

        // Same version should be allowed
        monitor.record(
            category = "test",
            event = "ok",
            fields = mapOf("version_name" to "1.8.33", "version_code" to 53)
        )
        
        // Mismatched version should record mixed_version_event_rejected
        monitor.record(
            category = "test",
            event = "fail",
            fields = mapOf("version_name" to "1.8.22", "version_code" to 42)
        )
        
        val traceContent = traceFile.readText()
        assertTrue("Should contain the normal event", traceContent.contains("\"event\":\"ok\""))
        assertTrue("Should contain rejection event", traceContent.contains("\"event\":\"mixed_version_event_rejected\""))
        assertTrue("Should contain mismatch details", traceContent.contains("\"session_version\":\"1.8.33\""))
        assertTrue("Should contain mismatch details", traceContent.contains("\"event_version\":\"1.8.22\""))
    }

    @Test
    fun `test report summarization detects mixed version`() {
        val traceFile = tempFolder.newFile("mixed_trace.jsonl")
        
        // Ordinal 1: Same version
        traceFile.appendText(RealJSONObject()
            .put("timestamp_ms", System.currentTimeMillis())
            .put("category", "test")
            .put("event", "ok")
            .put("details", RealJSONObject().put("app_version", "1.8.33").put("version_code", 53))
            .toString() + "\n")
            
        // Ordinal 2: Different version
        traceFile.appendText(RealJSONObject()
            .put("timestamp_ms", System.currentTimeMillis())
            .put("category", "test")
            .put("event", "mismatch")
            .put("details", RealJSONObject().put("app_version", "1.8.22").put("version_code", 42))
            .toString() + "\n")

        val metadata = ResearchMonitorReportMetadata(
            sessionId = "session-1",
            startedAt = System.currentTimeMillis() - 1000,
            finishedAt = System.currentTimeMillis(),
            appVersion = "1.8.33",
            versionCode = 53,
            device = "emulator",
            androidVersion = "14",
            monitoringStillActive = false,
            traceWasCapped = false
        )

        val reportFile = tempFolder.newFile("report.md")
        ResearchMonitorReportWriter.write(traceFile, reportFile, metadata)
        
        val reportContent = reportFile.readText()
        assertTrue("Report should contain provenance identity", reportContent.contains("- Provenance identity: MIXED_VERSION"))
        assertTrue("Report should contain warning", reportContent.contains("contains events from multiple app versions"))
        assertTrue("Report should list first mismatch", reportContent.contains("ordinal 2: expected 1.8.33, found 1.8.22"))
    }
}
