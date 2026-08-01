package com.david.openassistant.data.repository

import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.domain.model.ModelProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class LegacyConversationImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeRepository: FakeConversationRepository
    private lateinit var importer: LegacyConversationImporter
    private lateinit var filesDir: File
    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        prefs = FakeSharedPreferences()
        fakeRepository = FakeConversationRepository()
        importer = LegacyConversationImporter(
            repository = fakeRepository,
            preferences = prefs,
            conversationsDirectory = File(filesDir, "conversations_v5")
        )
    }

    @Test
    fun importMigratesValidJsonFiles() = runBlocking {
        val convDir = File(filesDir, "conversations_v5")
        convDir.mkdirs()
        
        val convId = "test-conv-1"
        val convFile = File(convDir, "$convId.conversation.json")
        val json = JSONObject()
            .put("id", convId)
            .put("title", "Legacy Title")
            .put("updatedAt", 1000L)
            .put("modelProfile", "AUTO")
            .put("messages", JSONArray())
        
        convFile.writeText(json.toString(), StandardCharsets.UTF_8)
        
        importer.importIfNeeded()
        
        assertEquals(1, fakeRepository.savedConversations.size)
        val saved = fakeRepository.savedConversations[0]
        assertEquals(convId, saved.id)
        assertEquals("Legacy Title", saved.title)
        assertEquals(ModelProfile.AUTO, saved.modelProfile)
        assertTrue(prefs.getBoolean("room_conversation_migration_complete", false))
    }

    @Test
    fun importIsIdempotent() = runBlocking {
        prefs.edit().putBoolean("room_conversation_migration_complete", true).apply()
        
        val convDir = File(filesDir, "conversations_v5")
        convDir.mkdirs()
        File(convDir, "test.conversation.json").writeText("{}", StandardCharsets.UTF_8)
        
        importer.importIfNeeded()
        
        assertEquals(0, fakeRepository.savedConversations.size)
    }

    private class FakeConversationRepository : ConversationRepository {
        val savedConversations = mutableListOf<StoredConversation>()
        var activeId: String? = null

        override fun getConversationSnapshot(): Flow<ConversationSnapshot> = flowOf(ConversationSnapshot(savedConversations, activeId ?: ""))
        override suspend fun saveConversation(conversation: StoredConversation) { savedConversations.add(conversation) }
        override suspend fun saveSnapshot(snapshot: ConversationSnapshot) { }
        override suspend fun deleteConversation(id: String) { }
        override suspend fun setActiveConversation(id: String?) { activeId = id }
        override suspend fun getConversation(id: String): StoredConversation? = savedConversations.find { it.id == id }
    }

    private class FakeSharedPreferences : android.content.SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun edit(): android.content.SharedPreferences.Editor = FakeEditor(values)
        override fun getStringSet(p0: String?, p1: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(p0: String?, p1: Int): Int = 0
        override fun getLong(p0: String?, p1: Long): Long = 0
        override fun getFloat(p0: String?, p1: Float): Float = 0f
        override fun contains(p0: String?): Boolean = values.containsKey(p0)
        override fun registerOnSharedPreferenceChangeListener(p0: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(p0: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val map: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            override fun putString(k: String, v: String?): android.content.SharedPreferences.Editor = apply { temp[k] = v }
            override fun putBoolean(k: String, v: Boolean): android.content.SharedPreferences.Editor = apply { temp[k] = v }
            override fun apply() { map.putAll(temp) }
            override fun commit(): Boolean { map.putAll(temp); return true }
            override fun putStringSet(p0: String?, p1: MutableSet<String>?): android.content.SharedPreferences.Editor = this
            override fun putInt(p0: String?, p1: Int): android.content.SharedPreferences.Editor = this
            override fun putLong(p0: String?, p1: Long): android.content.SharedPreferences.Editor = this
            override fun putFloat(p0: String?, p1: Float): android.content.SharedPreferences.Editor = this
            override fun remove(p0: String?): android.content.SharedPreferences.Editor = this
            override fun clear(): android.content.SharedPreferences.Editor = this
        }
    }
}
