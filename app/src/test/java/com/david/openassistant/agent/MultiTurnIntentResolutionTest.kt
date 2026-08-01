package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTurnIntentResolutionTest {

    @Test
    fun `test multi-turn bow regression preservation`() {
        val messages = listOf(
            ChatMessage(id = "m1", role = ChatRole.USER, content = "I'm looking to buy the best take down recurve bow over $500"),
            ChatMessage(id = "m2", role = ChatRole.ASSISTANT, content = "I can help with that. Any specific use case?"),
            ChatMessage(id = "m3", role = ChatRole.USER, content = "more Hunting/3D"),
            ChatMessage(id = "m4", role = ChatRole.ASSISTANT, content = "Got it. Any other requirements?"),
            ChatMessage(id = "m5", role = ChatRole.USER, content = "needs to have Modern ILF")
        )
        
        val resolved = ResolvedResearchRequest.resolveFromHistory(messages, "conv-1")
        
        val requestText = resolved.resolvedRequest
        
        // Assert base intent is present
        assertTrue("Base intent missing", requestText.contains("buy the best take down recurve bow over $500"))
        
        // Assert refinements are present
        assertTrue("Hunting/3D refinement missing", requestText.contains("Hunting/3D"))
        assertTrue("Modern ILF refinement missing", requestText.contains("Modern ILF"))
        
        // Assert all user message IDs are preserved
        assertEquals(listOf("m1", "m3", "m5"), resolved.sourceMessageIds)
        
        // Verify constraint objects
        assertTrue(resolved.requiredConstraints.any { it.text.contains("buy the best take down recurve bow over $500") })
        assertTrue(resolved.requiredConstraints.any { it.text.contains("more Hunting/3D") })
        assertTrue(resolved.requiredConstraints.any { it.text.contains("needs to have Modern ILF") })
    }
}
