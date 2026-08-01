package com.david.openassistant.domain

import android.content.Context
import android.net.Uri
import com.david.openassistant.data.database.OpenAssistantDatabase
import com.david.openassistant.data.local.AttachmentStore
import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.repository.LegacyConversationImporter
import com.david.openassistant.data.repository.RoomConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ConversationInteractor(context: Context) {
    private val database = OpenAssistantDatabase.getDatabase(context)
    private val repository = RoomConversationRepository(database)
    private val importer = LegacyConversationImporter(context, repository)
    private val attachmentStore = AttachmentStore(context)
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenAssistantConversationStore").apply { isDaemon = true }
    }

    suspend fun loadSnapshot(): ConversationSnapshot = withContext(Dispatchers.IO) {
        importer.importIfNeeded()
        val currentSnapshot = repository.getConversationSnapshot().first()
        if (currentSnapshot.conversations.isEmpty()) {
            val empty = com.david.openassistant.data.local.StoredConversation.empty()
            repository.saveConversation(empty)
            repository.setActiveConversation(empty.id)
            repository.getConversationSnapshot().first()
        } else {
            currentSnapshot
        }
    }

    fun saveSnapshot(snapshot: ConversationSnapshot) {
        persistenceExecutor.execute {
            kotlinx.coroutines.runBlocking {
                repository.saveSnapshot(snapshot)
            }
        }
    }

    suspend fun importImage(uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        attachmentStore.importImage(uri)
    }

    suspend fun deleteAttachment(attachment: ChatAttachment) = withContext(Dispatchers.IO) {
        attachmentStore.delete(attachment)
    }

    suspend fun pruneAttachments(allowedFileNames: Set<String>) = withContext(Dispatchers.IO) {
        attachmentStore.pruneTo(allowedFileNames)
    }

    fun shutdown() {
        persistenceExecutor.shutdown()
    }
}
