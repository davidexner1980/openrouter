package com.david.openassistant.data.database.dao

import androidx.room.*
import com.david.openassistant.data.database.entity.ActiveConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveConversationDao {
    @Query("SELECT active_conversation_id FROM conversation_selection WHERE id = 0")
    fun getActiveConversationId(): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setActiveConversationId(entity: ActiveConversationEntity)
}
