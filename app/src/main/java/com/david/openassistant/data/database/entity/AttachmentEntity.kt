package com.david.openassistant.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("message_id")]
)
data class AttachmentEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    val kind: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "page_count")
    val pageCount: Int,
    val ordinal: Int
)
