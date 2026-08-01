package com.david.openassistant.data.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResearchMonitorJsonlTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var monitor: ResearchMonitor
    private lateinit var rootDir: File

    @Before
    fun setup() {
        rootDir = tempFolder.newFolder("research_monitor")
        val prefs = FakeSharedPreferences()
        monitor = ResearchMonitor(
            preferences = prefs,
            rootDirectory = tempFolder.root,
            cacheDir = tempFolder.newFolder("cache"),
            packageName = "com.david.openassistant"
        )
        prefs.edit().putString("session_id", "test-session").putBoolean("active", true).commit()
    }

    @Test
    fun `test field truncation maintains valid JSON`() {
        val oversizedString = "A".repeat(20000)
        val fields = mapOf("content" to oversizedString)
        
        monitor.record("test", "event", fields = fields)
        
        val traceFile = File(tempFolder.root, "sessions/test-session.jsonl")
        assertTrue(traceFile.exists())
        
        val line = traceFile.readLines().first()
        val json = JSONObject(line)
        val details = json.getJSONObject("details")
        val content = details.get("content")
        
        assertTrue(content is JSONObject)
        val truncation = content as JSONObject
        assertTrue(truncation.getBoolean("truncated"))
        assertEquals(16384, truncation.getInt("retained_characters"))
        assertEquals(20000, truncation.getInt("original_redacted_characters"))
    }

    @Test
    fun `test unicode and nested structures`() {
        val fields = mapOf(
            "unicode" to "🚀 Assistant",
            "nested" to JSONObject().put("key", "value").put("list", JSONArray(listOf(1, 2, 3)))
        )
        
        monitor.record("test", "event", fields = fields)
        
        val traceFile = File(tempFolder.root, "sessions/test-session.jsonl")
        val line = traceFile.readLines().first()
        
        val json = JSONObject(line)
        val details = json.getJSONObject("details")
        assertEquals("🚀 Assistant", details.getString("unicode"))
        assertEquals("value", details.getJSONObject("nested").getString("key"))
    }

    private fun JSONArray(list: List<*>): org.json.JSONArray {
        val array = org.json.JSONArray()
        list.forEach { array.put(it) }
        return array
    }
}

private class FakeSharedPreferences : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = map
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : android.content.SharedPreferences.Editor {
        private val tempMap = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor = apply { tempMap[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): android.content.SharedPreferences.Editor = apply { tempMap[key] = values }
        override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor = apply { tempMap[key] = value }
        override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor = apply { tempMap[key] = value }
        override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor = apply { tempMap[key] = value }
        override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor = apply { tempMap[key] = value }
        override fun remove(key: String): android.content.SharedPreferences.Editor = apply { tempMap.remove(key) }
        override fun clear(): android.content.SharedPreferences.Editor = apply { tempMap.clear() }
        override fun commit(): Boolean { map.putAll(tempMap); return true }
        override fun apply() { commit() }
    }
}
