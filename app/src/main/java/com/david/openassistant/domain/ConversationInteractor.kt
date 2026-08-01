package com.david.openassistant.domain

import android.content.Context
import android.net.Uri
import com.david.openassistant.data.local.AttachmentStore
import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.local.ConversationStore
import com.david.openassistant.data.local.DEFAULT_CONVERSATION_TITLE
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.domain.model.ModelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ConversationInteractor(context: Context) {
    private val conversationStore = ConversationStore(context)
    private val attachmentStore = AttachmentStore(context)
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenAssistantConversationStore").apply { isDaemon = true }
    }

    suspend fun loadSnapshot(): ConversationSnapshot = withContext(Dispatchers.IO) {
        conversationStore.loadSnapshot()
    }

    fun saveSnapshot(snapshot: ConversationSnapshot) {
        persistenceExecutor.execute {
            runCatching { conversationStore.saveSnapshot(snapshot) }
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
