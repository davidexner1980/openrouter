package com.david.openassistant.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.util.AtomicFile
import androidx.core.content.edit
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatAttachmentKind
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import com.david.openassistant.domain.model.ModelProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Durable per-conversation store.
 *
 * Migrated from single-string SharedPreferences to AtomicFile per conversation.
 * SharedPreferences now carries only the active conversation selection and a
 * revision signal observed by the UI.
 */
class ConversationStore(
    context: Context? = null,
    baseDir: File? = null,
    prefs: SharedPreferences? = null
) {
    private val appContext = context?.applicationContext
    private val preferences = prefs ?: appContext!!.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val conversationsDirectory = baseDir ?: File(appContext!!.filesDir, CONVERSATIONS_DIRECTORY_NAME)

    fun loadSnapshot(): ConversationSnapshot = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        return loadSnapshotFromFilesLocked()
    }

    fun saveSnapshot(snapshot: ConversationSnapshot) = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val expectedFiles = snapshot.conversations.mapTo(mutableSetOf()) { conversationFileLocked(it.id).name }
        snapshot.conversations.forEach { writeConversationLocked(it, signal = false) }
        discoverConversationFilesLocked()
            .filter { it.name !in expectedFiles }
            .forEach { file ->
                file.delete()
                File(file.path + ATOMIC_BACKUP_SUFFIX).delete()
            }
        writeSelectionAndSignalLocked(snapshot.activeConversationId)
    }

    private fun loadSnapshotFromFilesLocked(): ConversationSnapshot {
        conversationsDirectory.mkdirs()
        val quarantined = mutableListOf<String>()
        val conversations = discoverConversationFilesLocked()
            .asSequence()
            .mapNotNull { file ->
                runCatching { readConversationLocked(file) }
                    .onFailure { error ->
                        android.util.Log.e("ConversationStore", "Failed to read conversation file: ${file.name}", error)
                        quarantineCorruptFileLocked(file, error)
                        quarantined.add(file.name)
                    }
                    .getOrNull()
            }
            .sortedByDescending { it.updatedAt }
            .toList()

        if (conversations.isEmpty()) {
            val empty = StoredConversation.empty()
            writeConversationLocked(empty, signal = false)
            writeSelectionAndSignalLocked(empty.id)
            return ConversationSnapshot(listOf(empty), empty.id)
        }

        val activeId = preferences.getString(KEY_ACTIVE_CONVERSATION_ID, null)
            ?.takeIf { id -> conversations.any { it.id == id } }
            ?: conversations.first().id

        return ConversationSnapshot(conversations, activeId)
    }

    private fun discoverConversationFilesLocked(): List<File> {
        conversationsDirectory.mkdirs()
        return conversationsDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    file.name.endsWith(CONVERSATION_FILE_SUFFIX) -> file
                    file.name.endsWith(CONVERSATION_FILE_SUFFIX + ATOMIC_BACKUP_SUFFIX) ->
                        File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun writeConversationLocked(conversation: StoredConversation, signal: Boolean = true) {
        conversationsDirectory.mkdirs()
        val target = conversationFileLocked(conversation.id)
        val atomicFile = AtomicFile(target)
        val text = encodeConversation(conversation).toString()
        val backup = File(target.path + ATOMIC_BACKUP_SUFFIX)
        
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(text.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            try { stream.fd.sync() } catch (_: Exception) {}
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            android.util.Log.e("ConversationStore", "Failed to write conversation ${conversation.id}", e)
            throw e
        } finally {
            if (backup.exists()) {
                backup.delete()
            }
        }

        // Verification & Fallback (Windows FS sync defense)
        if (!target.exists() || target.readText(StandardCharsets.UTF_8) != text) {
            try {
                target.writeText(text, StandardCharsets.UTF_8)
                if (backup.exists()) backup.delete()
            } catch (e: Exception) {
                android.util.Log.e("ConversationStore", "Fallback write failed for ${conversation.id}", e)
            }
        }
        
        if (signal) {
            // Re-write signal to notify UI
            writeSelectionAndSignalLocked(preferences.getString(KEY_ACTIVE_CONVERSATION_ID, null))
        }
    }

    private fun readConversationLocked(file: File): StoredConversation {
        val atomicFile = AtomicFile(file)
        val raw = try {
            atomicFile.openRead().use { it.bufferedReader(StandardCharsets.UTF_8).readText() }
        } catch (e: Exception) {
            // If atomic openRead fails, check if the base file exists and try a raw read
            if (file.exists()) {
                file.readText(StandardCharsets.UTF_8)
            } else throw e
        }
        return decodeConversation(JSONObject(raw))
    }

    private fun quarantineCorruptFileLocked(file: File, error: Throwable) {
        runCatching {
            val quarantineDir = File(conversationsDirectory, "quarantine")
            quarantineDir.mkdirs()
            val timestamp = System.currentTimeMillis()
            val quarantinedFile = File(quarantineDir, "${file.name}.$timestamp.corrupt")
            file.renameTo(quarantinedFile)
            
            val logFile = File(quarantineDir, "${file.name}.$timestamp.log")
            logFile.writeText("Error reading conversation file: ${error.message}\n${error.stackTraceToString()}")
            
            // Also move backup if present
            val backup = File(file.path + ATOMIC_BACKUP_SUFFIX)
            if (backup.exists()) {
                backup.renameTo(File(quarantineDir, "${backup.name}.$timestamp.corrupt"))
            }
        }
    }

    private fun conversationFileLocked(id: String): File {
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96)
        return File(conversationsDirectory, safeId + CONVERSATION_FILE_SUFFIX)
    }

    private fun writeSelectionAndSignalLocked(activeConversationId: String?) {
        check(
            runCatching {
                preferences.edit(commit = true) {
                    putString(KEY_ACTIVE_CONVERSATION_ID, activeConversationId)
                    putString(KEY_CONVERSATIONS_V4, newRevisionSignal())
                }
                true
            }.getOrDefault(false),
        ) { "Conversation state selection could not be persisted." }
    }

    private fun newRevisionSignal(): String = "v5:${System.currentTimeMillis()}:${UUID.randomUUID()}"

    private fun migrateLegacyIfNeededLocked() {
        if (preferences.getBoolean(KEY_MIGRATED_V5, false)) return
        conversationsDirectory.mkdirs()

        // Try to load from V4 SharedPreferences first
        val legacyRaw = preferences.getString(KEY_CONVERSATIONS_V4, null)
        if (legacyRaw?.startsWith("[") == true) {
            runCatching {
                val array = JSONArray(legacyRaw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val conversation = decodeConversation(item)
                    writeConversationLocked(conversation)
                }
            }
        } else {
            // Try older versions if V4 is empty
            val legacySnapshot = loadLegacySnapshotLocked()
            legacySnapshot.conversations.forEach(::writeConversationLocked)
            if (legacySnapshot.conversations.isNotEmpty()) {
                preferences.edit { putString(KEY_ACTIVE_CONVERSATION_ID, legacySnapshot.activeConversationId) }
            }
        }

        check(
            runCatching {
                preferences.edit(commit = true) {
                    putBoolean(KEY_MIGRATED_V5, true)
                    putString(KEY_CONVERSATIONS_V4, newRevisionSignal())
                }
                true
            }.getOrDefault(false),
        ) { "Conversation migration flag could not be persisted." }
    }

    private fun loadLegacySnapshotLocked(): ConversationSnapshot {
        // Migration logic from previous versions
        listOf(KEY_CONVERSATIONS_V3, KEY_CONVERSATIONS_V2).forEach { key ->
            loadSnapshotFromLegacyKey(key)?.let { return it }
        }

        val legacyMessages = loadLegacyMessages()
        if (legacyMessages.isEmpty()) return ConversationSnapshot(emptyList(), "")
        
        val now = System.currentTimeMillis()
        val conversation = StoredConversation(
            id = UUID.randomUUID().toString(),
            title = createConversationTitle(legacyMessages.firstUserMessageText().orEmpty()),
            updatedAt = now,
            selectedModelId = preferences.getString("selected_model_id", null),
            modelProfile = ModelProfile.MANUAL,
            messages = legacyMessages,
        )
        return ConversationSnapshot(listOf(conversation), conversation.id)
    }

    private fun loadSnapshotFromLegacyKey(key: String): ConversationSnapshot? {
        val raw = preferences.getString(key, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            val conversations = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(decodeConversation(item))
                }
            }
            if (conversations.isEmpty()) return null
            ConversationSnapshot(
                conversations = conversations.sortedByDescending { it.updatedAt },
                activeConversationId = preferences.getString(KEY_ACTIVE_CONVERSATION_ID, null)
                    ?: conversations.first().id,
            )
        }.getOrNull()
    }

    private fun loadLegacyMessages(): List<ChatMessage> {
        val raw = preferences.getString("current_conversation", null) ?: return emptyList()
        return runCatching { parseMessages(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun encodeConversation(conversation: StoredConversation): JSONObject = JSONObject()
        .put("id", conversation.id)
        .put("title", conversation.title)
        .put("updatedAt", conversation.updatedAt)
        .put("selectedModelId", conversation.selectedModelId ?: JSONObject.NULL)
        .put("modelProfile", conversation.modelProfile.name)
        .put("messages", JSONArray().apply {
            conversation.messages.forEach { message ->
                put(
                    JSONObject()
                        .put("id", message.id)
                        .put("role", message.role.name)
                        .put("content", message.content)
                        .put("attachments", JSONArray().apply {
                            message.attachments.forEach { attachment ->
                                put(
                                    JSONObject()
                                        .put("id", attachment.id)
                                        .put("kind", attachment.kind.name)
                                        .put("displayName", attachment.displayName)
                                        .put("mimeType", attachment.mimeType)
                                        .put("fileName", attachment.fileName)
                                        .put("sizeBytes", attachment.sizeBytes)
                                        .put("width", attachment.width)
                                        .put("height", attachment.height),
                                )
                            }
                        })
                )
            }
        })

    private fun decodeConversation(item: JSONObject): StoredConversation {
        val messages = parseMessages(item.optJSONArray("messages"))
        return StoredConversation(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = item.optString("title").ifBlank { createConversationTitle(messages.firstUserMessageText().orEmpty()) },
            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
            selectedModelId = item.optString("selectedModelId").takeIf { it.isNotBlank() && it != "null" },
            modelProfile = ModelProfile.fromStoredName(item.optString("modelProfile").takeIf { it.isNotBlank() }),
            messages = messages,
        )
    }

    private fun parseMessages(array: JSONArray?): List<ChatMessage> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = runCatching { ChatRole.valueOf(item.getString("role")) }.getOrNull() ?: continue
                val content = item.optString("content")
                val attachments = parseAttachments(item.optJSONArray("attachments"))
                if (content.isBlank() && attachments.isEmpty()) continue
                add(
                    ChatMessage(
                        id = item.optString("id").ifBlank { "restored-$index" },
                        role = role,
                        content = content,
                        isStreaming = false,
                        attachments = attachments,
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
                val kind = runCatching { ChatAttachmentKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
                val fileName = item.optString("fileName")
                val mimeType = item.optString("mimeType")
                if (fileName.isBlank() || fileName != File(fileName).name || mimeType.isBlank() || !mimeType.startsWith("image/")) continue
                add(
                    ChatAttachment(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        kind = kind,
                        displayName = item.optString("displayName").ifBlank { "Attached image" },
                        mimeType = mimeType,
                        fileName = fileName,
                        sizeBytes = item.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                        width = item.optInt("width", 0).coerceAtLeast(0),
                        height = item.optInt("height", 0).coerceAtLeast(0),
                    )
                )
            }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "openassistant_local_state"
        private const val KEY_CONVERSATIONS_V4 = "conversations_v4"
        private const val KEY_CONVERSATIONS_V3 = "conversations_v3"
        private const val KEY_CONVERSATIONS_V2 = "conversations_v2"
        private const val KEY_ACTIVE_CONVERSATION_ID = "active_conversation_id"
        private const val KEY_MIGRATED_V5 = "conversations_migrated_v5"
        private const val CONVERSATIONS_DIRECTORY_NAME = "conversations_v5"
        private const val CONVERSATION_FILE_SUFFIX = ".conversation.json"
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private val STORE_LOCK = Any()
    }
}

data class ConversationSnapshot(
    val conversations: List<StoredConversation>,
    val activeConversationId: String,
) {
    val activeConversation: StoredConversation
        get() = conversations.firstOrNull { it.id == activeConversationId } 
            ?: conversations.firstOrNull() 
            ?: StoredConversation.empty()

    fun attachmentFileNames(): Set<String> = conversations
        .asSequence()
        .flatMap { conversation -> conversation.messages.asSequence() }
        .flatMap { message -> message.attachments.asSequence() }
        .map { attachment -> attachment.fileName }
        .toSet()
}

data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val selectedModelId: String?,
    val modelProfile: ModelProfile,
    val messages: List<ChatMessage>,
) {
    companion object {
        fun empty(
            id: String = UUID.randomUUID().toString(),
            selectedModelId: String? = null,
            modelProfile: ModelProfile = ModelProfile.MANUAL,
        ): StoredConversation = StoredConversation(
            id = id,
            title = DEFAULT_CONVERSATION_TITLE,
            updatedAt = System.currentTimeMillis(),
            selectedModelId = selectedModelId,
            modelProfile = modelProfile,
            messages = emptyList(),
        )
    }
}

private fun List<ChatMessage>.firstUserMessageText(): String? =
    firstOrNull { it.role == ChatRole.USER }
        ?.content
        ?.takeIf { it.isNotBlank() }
