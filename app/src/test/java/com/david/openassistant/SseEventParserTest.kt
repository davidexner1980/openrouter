package com.david.openassistant

import com.david.openassistant.data.openrouter.SecretRedactor
import com.david.openassistant.data.openrouter.SseEvent
import com.david.openassistant.data.openrouter.SseEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SseEventParserTest {
    @Test
    fun ignoresBlankAndCommentLines() {
        assertSame(SseEvent.Ignore, SseEventParser.parse(""))
        assertSame(SseEvent.Ignore, SseEventParser.parse(": keep-alive"))
    }

    @Test
    fun parsesDataAndDoneEvents() {
        assertEquals(SseEvent.Data("{\"id\":\"abc\"}"), SseEventParser.parse("data: {\"id\":\"abc\"}"))
        assertSame(SseEvent.Done, SseEventParser.parse("data: [DONE]"))
    }

    @Test
    fun redactsExactCredentialFromErrors() {
        val key = "sensitive-value"
        assertEquals(
            "Authorization failed for [REDACTED]",
            SecretRedactor.redact("Authorization failed for $key", key),
        )
    }
}
