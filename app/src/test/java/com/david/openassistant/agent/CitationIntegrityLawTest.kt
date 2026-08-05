package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class CitationIntegrityLawTest {

    @Test
    fun `test reject self-authorization Law 5`() {
        val url = "https://unverified.com"
        val result = AgentStepResult(
            content = "Fact from unverified source.",
            summary = AgentApiSummary(),
            sources = listOf(AgentSourceCitation("Unverified", url, "some excerpt")),
            claims = listOf(
                AgentClaim(
                    taskId = "task-1",
                    text = "Claim citing unverified source.",
                    type = AgentClaimType.FACT,
                    confidence = 1.0,
                    support = AgentClaimSupport.SUPPORTED,
                    sourceUrls = listOf(url)
                )
            )
        )

        val report = CitationValidator.validateStepResult(result, emptyList())
        assertFalse("Citation without evidence must be invalid", report.isValid)
        assertEquals("Exact unverifiedUrl count", 1, report.unverifiedUrls.size)
        assertEquals(url, report.unverifiedUrls[0])
    }

    @Test
    fun `test unicode excerpt matching Law 7`() {
        val content = "The word for coffee is café or кофе or 咖啡."
        
        // Accented Latin
        assertTrue("Should match accented Latin", CitationValidator.containsExcerpt(content, "café").isReliable())
        // Cyrillic
        assertTrue("Should match Cyrillic", CitationValidator.containsExcerpt(content, "кофе").isReliable())
        // CJK
        assertTrue("Should match CJK", CitationValidator.containsExcerpt(content, "咖啡").isReliable())
    }

    @Test
    fun `test semantic boundary preservation Law 7`() {
        val content = "alpha beta gamma"
        
        // Correct boundary
        assertTrue("Should match on token boundary", CitationValidator.containsExcerpt(content, "alpha beta").isReliable())
        
        // Broken boundary
        assertFalse("Should not match partial tokens across boundaries", CitationValidator.containsExcerpt(content, "alphab eta").isReliable())
    }

    @Test
    fun `test flexible matching Law 8`() {
        val content = "This is a   TEST   content."
        
        // Whitespace normalization
        assertTrue("Should match with extra whitespace", CitationValidator.containsExcerpt(content, "a TEST content").isReliable())
        // Case-insensitivity
        assertTrue("Should match case-insensitively", CitationValidator.containsExcerpt(content, "this is a test").isReliable())
    }

    @Test
    fun `test fabricated passage rejection Law 7`() {
        val url = "https://example.com"
        val content = "The sky is blue."
        val evidence = listOf(
            AgentEvidence(
                kind = AgentEvidenceKind.WEB_RESEARCH,
                title = "Sky",
                summary = "Sky colors",
                content = content,
                sources = listOf(AgentSourceCitation("Sky", url))
            )
        )

        val result = AgentStepResult(
            content = "Fabricated report.",
            summary = AgentApiSummary(),
            sources = listOf(AgentSourceCitation("Sky", url, "The sky is red")) // Fabricated excerpt
        )

        val report = CitationValidator.validateStepResult(result, evidence)
        assertFalse("Fabricated passage must be rejected", report.isValid)
        assertEquals("Exact invalid-citation count", 1, report.invalidExcerpts.size)
        assertEquals("The sky is red", report.invalidExcerpts[0])
    }

    @Test
    fun `test validation counts with multiple issues`() {
        val url1 = "https://real.com"
        val url2 = "https://fake.com"
        val evidence = listOf(
            AgentEvidence(
                kind = AgentEvidenceKind.WEB_RESEARCH,
                title = "Real",
                summary = "Real info",
                content = "Valid content here.",
                sources = listOf(AgentSourceCitation("Real", url1))
            )
        )

        val result = AgentStepResult(
            content = "Mixed report.",
            summary = AgentApiSummary(),
            sources = listOf(
                AgentSourceCitation("Real", url1, "invalid excerpt"),
                AgentSourceCitation("Fake", url2, "fake excerpt")
            ),
            claims = listOf(
                AgentClaim(
                    taskId = "task-1",
                    text = "Claim 1",
                    type = AgentClaimType.FACT,
                    confidence = 1.0,
                    support = AgentClaimSupport.SUPPORTED,
                    sourceUrls = listOf(url2)
                )
            )
        )

        val report = CitationValidator.validateStepResult(result, evidence)
        assertFalse(report.isValid)
        assertEquals("Exact invalid-citation count", 2, report.invalidExcerpts.size)
        assertEquals("Exact unverifiedUrl count", 1, report.unverifiedUrls.size) // url2 is the only unverified URL
    }
}
