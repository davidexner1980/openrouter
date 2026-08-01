package com.david.openassistant.data.database.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.david.openassistant.data.database.entity.ConversationEntity
import com.david.openassistant.data.database.entity.MessageEntity

data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(
        entity = MessageEntity::class,
        parentColumn = "id",
        entityColumn = "conversation_id"
    )
    val messages: List<MessageWithAttachments>
)
