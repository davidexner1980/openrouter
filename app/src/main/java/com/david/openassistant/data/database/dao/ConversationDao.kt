package com.david.openassistant.data.database.dao

import androidx.room.*
import com.david.openassistant.data.database.entity.ConversationEntity
import com.david.openassistant.data.database.relation.ConversationWithMessages
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Transaction
    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun getAllConversationsWithMessages(): Flow<List<ConversationWithMessages>>

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationWithMessages(id: String): ConversationWithMessages?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT id FROM conversations")
    suspend fun getAllConversationIds(): List<String>
}
