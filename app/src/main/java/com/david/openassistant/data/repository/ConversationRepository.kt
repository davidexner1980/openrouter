package com.david.openassistant.data.repository

import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.local.StoredConversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getConversationSnapshot(): Flow<ConversationSnapshot>
    suspend fun saveConversation(conversation: StoredConversation)
    suspend fun saveSnapshot(snapshot: ConversationSnapshot)
    suspend fun deleteConversation(id: String)
    suspend fun setActiveConversation(id: String?)
    suspend fun getConversation(id: String): StoredConversation?
}
