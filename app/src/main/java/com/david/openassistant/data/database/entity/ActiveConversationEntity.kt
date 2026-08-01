package com.david.openassistant.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_selection")
data class ActiveConversationEntity(
    @PrimaryKey
    val id: Int = 0,
    @ColumnInfo(name = "active_conversation_id")
    val activeConversationId: String?
)
