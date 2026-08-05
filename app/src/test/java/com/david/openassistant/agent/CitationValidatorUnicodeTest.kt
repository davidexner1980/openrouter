package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Verified Unicode-aware excerpt matching.
 * Complies with Law 7 (Excerpt-Matching Law).
 */
class CitationValidatorUnicodeTest {

    @Test
    fun rejectsUnicodeExcerptAbsentFromVerifiedContent() {
        val content = "This is some content without Cyrillic."
        val excerpt = "Ошибка" // Non-ASCII excerpt not in content

        val result = CitationValidator.containsExcerpt(content, excerpt)
        
        assertFalse("Excerpt '$excerpt' should NOT be found in content", result.isReliable())
    }

    @Test
    fun matchesUnicodeExcerptPresentInVerifiedContent() {
        val content = "Данные подтверждены."
        val excerpt = "подтверждены"
        
        val result = CitationValidator.containsExcerpt(content, excerpt)
        assertTrue("Excerpt '$excerpt' should be found in content", result.isReliable())
    }
}
