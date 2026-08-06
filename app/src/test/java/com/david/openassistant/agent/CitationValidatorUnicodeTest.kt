package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class CitationValidatorUnicodeTest {

    @Test
    fun testUnicodeExcerptMatchingWithOffsets() {
        val content = "The word for coffee is café or кофе or 咖啡."
        
        // CJK
        val matchCJK = CitationValidator.containsExcerpt(content, "咖啡")
        assertTrue("Should match CJK", matchCJK.confidence.isReliable())
        assertEquals("咖啡", content.substring(matchCJK.passageStart!!, matchCJK.passageEnd!!))

        // Cyrillic
        val matchCyrillic = CitationValidator.containsExcerpt(content, "кофе")
        assertTrue("Should match Cyrillic", matchCyrillic.confidence.isReliable())
        assertEquals("кофе", content.substring(matchCyrillic.passageStart!!, matchCyrillic.passageEnd!!))
        
        // Accented Latin (Exact)
        val matchAccented = CitationValidator.containsExcerpt(content, "café")
        assertTrue("Should match accented Latin", matchAccented.confidence.isReliable())
        assertEquals("café", content.substring(matchAccented.passageStart!!, matchAccented.passageEnd!!))
    }

    @Test
    fun testUnicodeTokenBoundaryMatching() {
        val content = "Данные (подтверждены) успешно."
        val excerpt = "данные подтверждены"
        
        val result = CitationValidator.containsExcerpt(content, excerpt)
        assertTrue("Should match through punctuation and normalization", result.confidence.isReliable())
        assertEquals(CitationBindingMethod.NORMALIZED_TOKEN_BOUNDARY, result.bindingMethod)
        
        val passage = content.substring(result.passageStart!!, result.passageEnd!!)
        assertEquals("Данные (подтверждены", passage)
    }
}
