package com.david.openassistant.data.local

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationHandler
import java.nio.charset.StandardCharsets

class ConversationStoreAtomicityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: ConversationStore
    private lateinit var baseDir: File

    @Before
    fun setup() {
        baseDir = tempFolder.newFolder("conversations")
        val prefs = createFakePrefs()
        store = ConversationStore(baseDir = baseDir, prefs = prefs)
    }

    private fun createFakePrefs(): SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getString" -> map[args!![0] as String] ?: args[1]
                "getBoolean" -> map[args!![0] as String] ?: args[1]
                "getLong" -> map[args!![0] as String] ?: args[1]
                "getInt" -> map[args!![0] as String] ?: args[1]
                "edit" -> createFakeEditor(map)
                "registerOnSharedPreferenceChangeListener" -> Unit
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            handler
        ) as SharedPreferences
    }

    private fun createFakeEditor(map: MutableMap<String, Any?>): SharedPreferences.Editor {
        val tempMap = mutableMapOf<String, Any?>()
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "putString", "putBoolean", "putLong", "putInt", "remove" -> {
                    tempMap[args!![0] as String] = args[1]
                    proxy
                }
                "apply", "commit" -> {
                    map.putAll(tempMap)
                    if (method.name == "commit") true else Unit
                }
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            handler
        ) as SharedPreferences.Editor
    }

    @Test
    fun testSuccessfulAtomicWrite() {
        val conversation = StoredConversation.empty(id = "conv-1")
        store.saveSnapshot(ConversationSnapshot(listOf(conversation), conversation.id))
        
        val targetFile = File(baseDir, "conv-1.conversation.json")
        assertTrue(targetFile.exists())
        assertFalse(File(baseDir, "conv-1.conversation.json.bak").exists())
        
        val reloaded = store.loadSnapshot().activeConversation
        assertEquals("conv-1", reloaded.id)
    }

    @Test
    fun testCorruptionHandlingAndQuarantine() {
        val convId = "corrupt-1"
        val targetFile = File(baseDir, "$convId.conversation.json")
        baseDir.mkdirs()
        targetFile.writeText("INVALID JSON", StandardCharsets.UTF_8)
        
        val snapshot = store.loadSnapshot()
        
        // Should create a fresh empty conversation because the only one is corrupt
        assertNotEquals(convId, snapshot.activeConversationId)
        
        val quarantineDir = File(baseDir, "quarantine")
        assertTrue(quarantineDir.exists())
        val quarantinedFiles = quarantineDir.listFiles()?.toList().orEmpty()
        assertTrue(quarantinedFiles.any { it.name.startsWith(targetFile.name) && it.name.endsWith(".corrupt") })
        assertTrue(quarantinedFiles.any { it.name.startsWith(targetFile.name) && it.name.endsWith(".log") })
    }
}
