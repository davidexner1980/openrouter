package com.david.openassistant.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatAttachmentKind
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.domain.model.ModelProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

class LegacyConversationImporter(
    private val repository: ConversationRepository,
    private val preferences: android.content.SharedPreferences,
    private val conversationsDirectory: File
) {
    constructor(context: Context, repository: ConversationRepository) : this(
        repository = repository,
        preferences = context.applicationContext.getSharedPreferences("openassistant_local_state", Context.MODE_PRIVATE),
        conversationsDirectory = File(context.applicationContext.filesDir, "conversations_v5")
    )

    suspend fun importIfNeeded() {
        if (preferences.getBoolean(KEY_ROOM_MIGRATED, false)) return

        val snapshot = loadLegacySnapshot()
        snapshot.conversations.forEach { conversation ->
            repository.saveConversation(conversation)
        }
        repository.setActiveConversation(snapshot.activeConversationId.takeIf { it.isNotBlank() })

        preferences.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
    }

    private fun loadLegacySnapshot(): LegacySnapshot {
        if (!conversationsDirectory.exists()) return LegacySnapshot(emptyList(), "")

        val files = conversationsDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".conversation.json") }

        val conversations = files.mapNotNull { file ->
            runCatching { readConversation(file) }.getOrNull()
        }

        val activeId = preferences.getString("active_conversation_id", null)
            ?: conversations.firstOrNull()?.id
            ?: ""

        return LegacySnapshot(conversations, activeId)
    }

    private fun readConversation(file: File): StoredConversation {
        val atomicFile = AtomicFile(file)
        val raw = atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return decodeConversation(JSONObject(raw))
    }

    private fun decodeConversation(item: JSONObject): StoredConversation {
        val messages = parseMessages(item.optJSONArray("messages"))
        return StoredConversation(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = item.optString("title"),
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            selectedModelId = item.optString("selectedModelId").takeIf { it != "null" },
            modelProfile = ModelProfile.fromStoredName(item.optString("modelProfile")),
            messages = messages,
        )
    }

    private fun parseMessages(array: JSONArray?): List<ChatMessage> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = runCatching { ChatRole.valueOf(item.getString("role")) }.getOrNull() ?: continue
                add(
                    ChatMessage(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        role = role,
                        content = item.optString("content"),
                        attachments = parseAttachments(item.optJSONArray("attachments"))
                    )
                )
            }
        }
    }

    private fun parseAttachments(array: JSONArray?): List<ChatAttachment> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = runCatching { ChatAttachmentKind.valueOf(item.getString("kind")) }.getOrNull() ?: continue
                add(
                    ChatAttachment(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        kind = kind,
                        displayName = item.optString("displayName"),
                        mimeType = item.optString("mimeType"),
                        fileName = item.optString("fileName"),
                        sizeBytes = item.optLong("sizeBytes"),
                        width = item.optInt("width"),
                        height = item.optInt("height"),
                        pageCount = item.optInt("pageCount")
                    )
                )
            }
        }
    }

    private data class LegacySnapshot(
        val conversations: List<StoredConversation>,
        val activeConversationId: String
    )

    companion object {
        private const val KEY_ROOM_MIGRATED = "room_conversation_migration_complete"
    }
}
