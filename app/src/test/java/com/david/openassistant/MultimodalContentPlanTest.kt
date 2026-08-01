package com.david.openassistant

import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatAttachmentKind
import com.david.openassistant.data.openrouter.ChatContentPartPlan
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import com.david.openassistant.data.openrouter.toMultimodalContentPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalContentPlanTest {
    @Test
    fun textIsPlacedBeforeImages() {
        val attachment = ChatAttachment(
            id = "image-1",
            kind = ChatAttachmentKind.IMAGE,
            displayName = "test.jpg",
            mimeType = "image/jpeg",
            fileName = "test.jpg",
            sizeBytes = 10,
            width = 100,
            height = 80,
        )
        val plan = ChatMessage(
            id = "message-1",
            role = ChatRole.USER,
            content = "What is visible?",
            attachments = listOf(attachment),
        ).toMultimodalContentPlan()

        assertEquals(2, plan.size)
        assertTrue(plan[0] is ChatContentPartPlan.Text)
        assertTrue(plan[1] is ChatContentPartPlan.Image)
    }

    @Test
    fun imageOnlyMessagesStillProduceAnImagePart() {
        val attachment = ChatAttachment(
            id = "image-1",
            kind = ChatAttachmentKind.IMAGE,
            displayName = "test.jpg",
            mimeType = "image/jpeg",
            fileName = "test.jpg",
            sizeBytes = 10,
            width = 100,
            height = 80,
        )
        val plan = ChatMessage(
            id = "message-1",
            role = ChatRole.USER,
            content = "",
            attachments = listOf(attachment),
        ).toMultimodalContentPlan()

        assertEquals(1, plan.size)
        assertTrue(plan.single() is ChatContentPartPlan.Image)
    }
}
