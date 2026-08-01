package com.david.openassistant

import com.david.openassistant.data.local.DEFAULT_CONVERSATION_TITLE
import com.david.openassistant.data.local.createConversationTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationNamingTest {
    @Test
    fun blankMessageUsesDefaultTitle() {
        assertEquals(DEFAULT_CONVERSATION_TITLE, createConversationTitle("   \n  "))
    }

    @Test
    fun whitespaceIsNormalized() {
        assertEquals("Build a better Android app", createConversationTitle("Build   a better\nAndroid app"))
    }

    @Test
    fun longTitlesAreBounded() {
        val title = createConversationTitle("a".repeat(100))
        assertEquals(48, title.length)
        assertEquals("...", title.takeLast(3))
    }
}
