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
                    id = "c1",
                    claimFingerprint = "fp1",
                    taskId = "task-1",
                    text = "Claim citing unverified source.",
                    type = AgentClaimType.FACT,
                    confidence = 1.0,
                    support = AgentClaimSupport.SUPPORTED,
                    sourceUrls = listOf(url)
                )
            )
        )

        // No evidence supplied, validator should find no matching snapshots
        val report = CitationValidator.validateStepResult(result, emptyList())
        assertFalse("Citation without evidence must be invalid", report.isValid)
        assertEquals("Exact unverifiedUrl count", 1, report.unverifiedUrls.size)
        assertEquals(url, report.unverifiedUrls[0])
    }

    @Test
    fun `test unicode excerpt matching Law 7`() {
        val content = "The word for coffee is café or кофе or 咖啡."
        
        // Accented Latin
        assertTrue("Should match accented Latin", CitationValidator.containsExcerpt(content, "café").confidence.isReliable())
        // Cyrillic
        assertTrue("Should match Cyrillic", CitationValidator.containsExcerpt(content, "кофе").confidence.isReliable())
        // CJK
        assertTrue("Should match CJK", CitationValidator.containsExcerpt(content, "咖啡").confidence.isReliable())
    }

    @Test
    fun `test semantic boundary preservation Law 7`() {
        val content = "alpha beta gamma"
        
        // Correct boundary
        assertTrue("Should match on token boundary", CitationValidator.containsExcerpt(content, "alpha beta").confidence.isReliable())
        
        // Broken boundary
        assertFalse("Should not match partial tokens across boundaries", CitationValidator.containsExcerpt(content, "alphab eta").confidence.isReliable())
    }

    @Test
    fun `test flexible matching Law 8`() {
        val content = "This is a   TEST   content."
        
        // Whitespace normalization
        assertTrue("Should match with extra whitespace", CitationValidator.containsExcerpt(content, "a TEST content").confidence.isReliable())
        // Case-insensitivity
        assertTrue("Should match case-insensitively", CitationValidator.containsExcerpt(content, "this is a test").confidence.isReliable())
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
                    id = "c1",
                    taskId = "task-1",
                    text = "Claim 1",
                    type = AgentClaimType.FACT,
                    confidence = 1.0,
                    support = AgentClaimSupport.SUPPORTED,
                    sourceUrls = listOf(url2),
                    claimFingerprint = "fp1"
                )
            )
        )

        val report = CitationValidator.validateStepResult(result, evidence)
        assertFalse(report.isValid)
        assertEquals("Exact invalid-citation count", 2, report.invalidExcerpts.size)
        // url2 is the only unverified URL in factual claims, but both url1 and url2 might be in citation report if they lack source reads
        assertTrue(report.unverifiedUrls.contains(url2))
    }

    @Test
    fun `test semantic opposition rejection`() {
        val url = "https://a.com"
        val content = "Revenue rose to $10 million."
        val hash = FingerprintUtils.hash(content)
        val docId = scopedSourceDocumentId(url)
        val readId = scopedSourceReadId(url, hash)
        val read = SourceRead(
            id = readId, url = url, canonicalUrl = url, documentId = docId,
            contentHash = hash, httpCode = 200, contentType = "text/plain", content = content,
            sourceRole = "research", authorityScore = 10, provenance = SourceReadProvenance.VERIFIED_FETCH
        )

        val binding = CitationBinding.createLegacy(
            claimId = "c1", sourceReadId = readId, documentId = docId, contentHash = hash,
            citationExcerpt = "Revenue rose to $10 million", passageStart = 0, passageEnd = content.length,
            passageHash = hash, bindingMethod = CitationBindingMethod.EXACT
        )

        val claim = AgentClaim(
            id = "c1", taskId = "task-1", text = "Revenue fell to $10 million.", // OPPOSITE
            type = AgentClaimType.FACT, confidence = 1.0, support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(url),
            citationBindings = listOf(binding), claimFingerprint = "fp1"
        )

        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(read))
        assertTrue("Opposite polarity must be rejected", decision is FactualClaimSupportDecision.Unsupported)
        val unsupported = decision as FactualClaimSupportDecision.Unsupported
        assertTrue(unsupported.reasons.contains("claim_passage_contradiction_detected"))
    }

    @Test
    fun `test legacy provenance partial support`() {
        val url = "https://a.com"
        val content = "Verified facts."
        val hash = FingerprintUtils.hash(content)
        val docId = scopedSourceDocumentId(url)
        val readId = scopedSourceReadId(url, hash)
        val read = SourceRead(
            id = readId, url = url, canonicalUrl = url, documentId = docId,
            contentHash = hash, httpCode = 200, contentType = "text/plain", content = content,
            sourceRole = "research", authorityScore = 10,
            provenance = SourceReadProvenance.LEGACY_ASSUMED // LEGACY
        )

        val binding = CitationBinding.createLegacy(
            claimId = "c1", sourceReadId = readId, documentId = docId, contentHash = hash,
            citationExcerpt = "Verified facts", passageStart = 0, passageEnd = content.length,
            passageHash = hash, bindingMethod = CitationBindingMethod.EXACT
        )

        val claim = AgentClaim(
            id = "c1", taskId = "task-1", text = "Verified facts",
            type = AgentClaimType.FACT, confidence = 1.0, support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(url),
            citationBindings = listOf(binding), claimFingerprint = "fp1"
        )

        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(read))
        assertTrue("LEGACY_ASSUMED should yield PartialBound", decision is FactualClaimSupportDecision.PartiallyBound)
        val partial = decision as FactualClaimSupportDecision.PartiallyBound
        assertTrue(partial.reasons.contains("legacy_evidence_requires_revalidation"))
    }

    @Test
    fun `test deterministic binding identity`() {
        val identity = CitationBindingIdentity(
            schemaVersion = 1, claimFingerprint = "clm-1", sourceReadId = "src-1",
            documentId = "doc-1", contentHash = "h1", passageHash = "p1",
            bindingMethod = CitationBindingMethod.EXACT
        )
        val fp1 = calculateCitationBindingFingerprint(identity)
        val fp2 = calculateCitationBindingFingerprint(identity)
        
        assertEquals("Fingerprint must be deterministic", fp1, fp2)
        assertEquals("ID must be deterministic", scopedCitationBindingId(fp1), scopedCitationBindingId(fp2))
    }

    @Test
    fun `test merge citation bindings determinism`() {
        val b1 = CitationBinding(
            id = "b1", claimId = "c1", sourceReadId = "s1", documentId = "d1", contentHash = "h1",
            citationExcerpt = "e1", confidence = 0.5, identitySchemaVersion = 1, logicalFingerprint = "f1"
        )
        val b2 = CitationBinding(
            id = "b2", claimId = "c1", sourceReadId = "s1", documentId = "d1", contentHash = "h1",
            citationExcerpt = "e1", confidence = 0.9, identitySchemaVersion = 1, logicalFingerprint = "f1"
        )
        
        val merged1 = mergeCitationBindings(listOf(b1), listOf(b2))
        val merged2 = mergeCitationBindings(listOf(b2), listOf(b1))
        
        assertEquals(1, merged1.size)
        assertEquals(1, merged2.size)
        assertEquals(0.9, merged1[0].confidence, 0.001)
        assertEquals(0.9, merged2[0].confidence, 0.001)
        assertEquals(merged1[0].id, merged2[0].id)
    }
}
