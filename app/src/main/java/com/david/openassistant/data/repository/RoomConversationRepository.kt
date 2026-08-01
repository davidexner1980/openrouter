package com.david.openassistant.data.repository

import androidx.room.withTransaction
import com.david.openassistant.data.database.OpenAssistantDatabase
import com.david.openassistant.data.database.entity.ActiveConversationEntity
import com.david.openassistant.data.database.mapper.toDomain
import com.david.openassistant.data.database.mapper.toEntity
import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.local.StoredConversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class RoomConversationRepository(
    private val database: OpenAssistantDatabase
) : ConversationRepository {

    private val conversationDao = database.conversationDao()
    private val messageDao = database.messageDao()
    private val attachmentDao = database.attachmentDao()
    private val activeConversationDao = database.activeConversationDao()

    override fun getConversationSnapshot(): Flow<ConversationSnapshot> {
        return combine(
            conversationDao.getAllConversationsWithMessages(),
            activeConversationDao.getActiveConversationId()
        ) { conversations, activeId ->
            val domainConversations = conversations.map { it.toDomain() }
            val finalActiveId = activeId?.takeIf { id -> domainConversations.any { it.id == id } }
                ?: domainConversations.firstOrNull()?.id
                ?: ""
            
            ConversationSnapshot(domainConversations, finalActiveId)
        }
    }

    override suspend fun saveConversation(conversation: StoredConversation) {
        database.withTransaction {
            saveConversationInternal(conversation)
        }
    }

    override suspend fun saveSnapshot(snapshot: ConversationSnapshot) {
        database.withTransaction {
            snapshot.conversations.forEach { conversation ->
                saveConversationInternal(conversation)
            }
            
            // Sync deletions
            val snapshotIds = snapshot.conversations.map { it.id }.toSet()
            val existingIds = conversationDao.getAllConversationIds()
            existingIds.filter { it !in snapshotIds }.forEach { id ->
                conversationDao.deleteConversation(id)
            }
            
            setActiveConversation(snapshot.activeConversationId)
        }
    }

    private suspend fun saveConversationInternal(conversation: StoredConversation) {
        conversationDao.insertConversation(conversation.toEntity())
        messageDao.deleteMessagesForConversation(conversation.id)
        
        val messageEntities = conversation.messages.mapIndexed { index, message ->
            message.toEntity(conversation.id, index)
        }
        if (messageEntities.isNotEmpty()) {
            messageDao.insertMessages(messageEntities)
        }
        
        val attachmentEntities = conversation.messages.flatMap { message ->
            message.attachments.mapIndexed { index, attachment ->
                attachment.toEntity(message.id, index)
            }
        }
        if (attachmentEntities.isNotEmpty()) {
            attachmentDao.insertAttachments(attachmentEntities)
        }
    }

    override suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversation(id)
    }

    override suspend fun setActiveConversation(id: String?) {
        activeConversationDao.setActiveConversationId(
            ActiveConversationEntity(activeConversationId = id)
        )
    }

    override suspend fun getConversation(id: String): StoredConversation? {
        return conversationDao.getConversationWithMessages(id)?.toDomain()
    }
}
