package com.david.openassistant.data.database.mapper

import com.david.openassistant.data.database.entity.AttachmentEntity
import com.david.openassistant.data.database.entity.ConversationEntity
import com.david.openassistant.data.database.entity.MessageEntity
import com.david.openassistant.data.database.relation.ConversationWithMessages
import com.david.openassistant.data.database.relation.MessageWithAttachments
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatAttachmentKind
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import com.david.openassistant.domain.model.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ConversationMappersTest {

    @Test
    fun conversationMappingIsDeterministic() {
        val conversation = StoredConversation(
            id = "conv-1",
            title = "Test Title",
            updatedAt = 123456789L,
            selectedModelId = "model-1",
            modelProfile = ModelProfile.AUTO,
            messages = emptyList()
        )

        val entity = conversation.toEntity()

        assertEquals("conv-1", entity.id)
        assertEquals("Test Title", entity.title)
        assertEquals(123456789L, entity.updatedAt)
        assertEquals("model-1", entity.selectedModelId)
        assertEquals("AUTO", entity.modelProfile)
    }

    @Test
    fun fullConversationRoundTrip() {
        val attachment = ChatAttachment(
            id = "att-1",
            kind = ChatAttachmentKind.IMAGE,
            displayName = "Image",
            mimeType = "image/jpeg",
            fileName = "file.jpg",
            sizeBytes = 100L,
            width = 800,
            height = 600
        )

        val message = ChatMessage(
            id = "msg-1",
            role = ChatRole.USER,
            content = "Hello",
            attachments = listOf(attachment)
        )

        val conversation = StoredConversation(
            id = "conv-1",
            title = "Title",
            updatedAt = 1L,
            selectedModelId = null,
            modelProfile = ModelProfile.MANUAL,
            messages = listOf(message)
        )

        // Domain -> Entity
        val convEntity = conversation.toEntity()
        val msgEntity = message.toEntity("conv-1", 0)
        val attEntity = attachment.toEntity("msg-1", 0)

        // Entity -> Domain
        val msgWithAtts = MessageWithAttachments(
            message = msgEntity,
            attachments = listOf(attEntity)
        )
        val convWithMsgs = ConversationWithMessages(
            conversation = convEntity,
            messages = listOf(msgWithAtts)
        )

        val domain = convWithMsgs.toDomain()

        assertEquals(conversation.id, domain.id)
        assertEquals(conversation.title, domain.title)
        assertEquals(conversation.updatedAt, domain.updatedAt)
        assertEquals(conversation.modelProfile, domain.modelProfile)
        assertEquals(1, domain.messages.size)
        
        val domainMsg = domain.messages[0]
        assertEquals(message.id, domainMsg.id)
        assertEquals(message.role, domainMsg.role)
        assertEquals(message.content, domainMsg.content)
        assertEquals(1, domainMsg.attachments.size)

        val domainAtt = domainMsg.attachments[0]
        assertEquals(attachment.id, domainAtt.id)
        assertEquals(attachment.kind, domainAtt.kind)
        assertEquals(attachment.fileName, domainAtt.fileName)
    }
}
