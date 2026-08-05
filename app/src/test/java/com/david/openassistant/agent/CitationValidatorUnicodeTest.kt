package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class CitationValidatorUnicodeTest {

    @Test
    fun testUnicodeExcerptMatchingErroneousPass() {
        val content = "This is some content without Cyrillic."
        val excerpt = "Ошибка" // Non-ASCII excerpt not in content

        // Currently, this returns true because it normalizes both to empty string or strips all characters
        val result = CitationValidator.containsExcerpt(content, excerpt)
        
        // REPRODUCTION: This assertion SHOULD fail if the code was correct, 
        // but currently it will likely pass (result will be true).
        // Actually, I want to assert that it is FALSE.
        assertFalse("Excerpt '$excerpt' should NOT be found in content", result)
    }

    @Test
    fun testUnicodeExcerptMatchingCorrectPass() {
        val content = "Данные подтверждены."
        val excerpt = "подтверждены"
        
        val result = CitationValidator.containsExcerpt(content, excerpt)
        assertTrue("Excerpt '$excerpt' should be found in content", result)
    }
}
