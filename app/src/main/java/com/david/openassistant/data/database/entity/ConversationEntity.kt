package com.david.openassistant.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "selected_model_id")
    val selectedModelId: String?,
    @ColumnInfo(name = "model_profile")
    val modelProfile: String
)
