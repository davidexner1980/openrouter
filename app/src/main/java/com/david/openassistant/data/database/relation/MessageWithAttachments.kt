package com.david.openassistant.data.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.david.openassistant.data.database.entity.AttachmentEntity
import com.david.openassistant.data.database.entity.MessageEntity

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "message_id"
    )
    val attachments: List<AttachmentEntity>
)
