package com.david.openassistant.data.openrouter

sealed interface ChatContentPartPlan {
    data class Text(val text: String) : ChatContentPartPlan
    data class Image(val attachment: ChatAttachment) : ChatContentPartPlan
    data class Pdf(val attachment: ChatAttachment) : ChatContentPartPlan
}

fun ChatMessage.toMultimodalContentPlan(): List<ChatContentPartPlan> = buildList {
    if (content.isNotBlank()) add(ChatContentPartPlan.Text(content))
    attachments.forEach { attachment ->
        when (attachment.kind) {
            ChatAttachmentKind.IMAGE -> add(ChatContentPartPlan.Image(attachment))
            ChatAttachmentKind.PDF -> add(ChatContentPartPlan.Pdf(attachment))
        }
    }
}
