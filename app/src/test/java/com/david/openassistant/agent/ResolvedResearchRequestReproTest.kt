package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class ResolvedResearchRequestReproTest {

    @Test
    fun testResolveFromHistory_withManualRequest() {
        val conversationId = UUID.randomUUID().toString()
        val manualRequest = "Test research request"
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = manualRequest,
        )
        val messages = listOf(userMessage)
        
        val resolved = ResolvedResearchRequest.resolveFromHistory(messages, conversationId)
        
        assertNotEquals("Resolved request should not be blank", "", resolved.resolvedRequest)
        assert(resolved.resolvedRequest.contains("Test research request"))
    }

    @Test
    fun testResolveFromHistory_emptyHistory() {
        val conversationId = UUID.randomUUID().toString()
        val messages = emptyList<ChatMessage>()
        
        val resolved = ResolvedResearchRequest.resolveFromHistory(messages, conversationId)
        
        assert(resolved.resolvedRequest.isBlank())
    }
}
