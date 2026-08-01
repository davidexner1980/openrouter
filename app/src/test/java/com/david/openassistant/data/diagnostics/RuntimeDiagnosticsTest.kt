package com.david.openassistant.data.diagnostics

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class RuntimeDiagnosticsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var diagDir: File
    private lateinit var diagnostics: RuntimeDiagnostics
    private val capturedEvents = mutableListOf<CapturedEvent>()

    private data class CapturedEvent(
        val level: String,
        val event: String,
        val fields: Map<String, Any?>
    )

    @Before
    fun setUp() {
        diagDir = tempFolder.newFolder("diagnostics")
        val monitorDir = tempFolder.newFolder("monitor")
        val cacheDir = tempFolder.newFolder("cache")
        
        val monitor = object : ResearchMonitor(createFakePrefs(), monitorDir, cacheDir) {
            override fun record(
                category: String,
                event: String,
                level: String,
                correlationId: String?,
                targetSessionId: String?,
                fields: Map<String, Any?>
            ) {
                capturedEvents.add(CapturedEvent(level, event, fields))
            }
        }
        
        diagnostics = RuntimeDiagnostics(diagDir, monitor)
    }

    @Test
    fun scopedDiagnosticsAppendsFields() {
        val scoped = diagnostics.withContext(mapOf("scope_key" to "scope_value"))
        scoped.info("test_event", mapOf("local_key" to "local_value"))
        
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals("test_event", event.event)
        assertEquals("scope_value", event.fields["scope_key"])
        assertEquals("local_value", event.fields["local_key"])
    }

    @Test
    fun diagnosticTimerLogsDuration() {
        val timer = diagnostics.startTimer("timed_event", "test_component", mapOf("start_key" to "start_value"))
        Thread.sleep(10)
        timer.stop(mapOf("stop_key" to "stop_value"))
        
        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0]
        assertEquals("timed_event", event.event)
        assertEquals("start_value", event.fields["start_key"])
        assertEquals("stop_value", event.fields["stop_key"])
        assertTrue(event.fields.containsKey("duration_ms"))
        val duration = event.fields["duration_ms"] as Long
        assertTrue(duration >= 10)
    }

    private fun createFakePrefs(): SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> map[args[0] as String] ?: args[1]
                "getBoolean" -> map[args[0] as String] ?: args[1]
                "getLong" -> map[args[0] as String] ?: args[1]
                "getInt" -> map[args[0] as String] ?: args[1]
                "edit" -> Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)
                ) { _, m, a ->
                    when (m.name) {
                        "putString" -> { map[a[0] as String] = a[1]; null }
                        "putBoolean" -> { map[a[0] as String] = a[1]; null }
                        "putLong" -> { map[a[0] as String] = a[1]; null }
                        "putInt" -> { map[a[0] as String] = a[1]; null }
                        "commit" -> true
                        "apply" -> Unit
                        else -> null
                    }
                }
                else -> null
            }
        } as SharedPreferences
    }
}
