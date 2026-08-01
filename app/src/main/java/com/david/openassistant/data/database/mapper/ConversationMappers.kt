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

fun StoredConversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    title = title,
    updatedAt = updatedAt,
    selectedModelId = selectedModelId,
    modelProfile = modelProfile.name
)

fun ConversationWithMessages.toDomain(): StoredConversation = StoredConversation(
    id = conversation.id,
    title = conversation.title,
    updatedAt = conversation.updatedAt,
    selectedModelId = conversation.selectedModelId,
    modelProfile = ModelProfile.fromStoredName(conversation.modelProfile),
    messages = messages.sortedBy { it.message.ordinal }.map { it.toDomain() }
)

fun ChatMessage.toEntity(conversationId: String, ordinal: Int): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.name,
    content = content,
    ordinal = ordinal,
    createdAt = System.currentTimeMillis()
)

fun MessageWithAttachments.toDomain(): ChatMessage = ChatMessage(
    id = message.id,
    role = ChatRole.valueOf(message.role),
    content = message.content,
    isStreaming = false,
    attachments = attachments.sortedBy { it.ordinal }.map { it.toDomain() }
)

fun ChatAttachment.toEntity(messageId: String, ordinal: Int): AttachmentEntity = AttachmentEntity(
    id = id,
    messageId = messageId,
    kind = kind.name,
    displayName = displayName,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    pageCount = pageCount,
    ordinal = ordinal
)

fun AttachmentEntity.toDomain(): ChatAttachment = ChatAttachment(
    id = id,
    kind = ChatAttachmentKind.valueOf(kind),
    displayName = displayName,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    pageCount = pageCount
)
