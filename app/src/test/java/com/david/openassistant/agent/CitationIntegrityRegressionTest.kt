package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CitationIntegrityRegressionTest {

    private val taskId = "task-1"
    private val url = "https://example.com/source"
    private val content = "The speed of light is approximately 299,792,458 meters per second in a vacuum."
    private val hash = FingerprintUtils.hash(content)
    private val docId = scopedSourceDocumentId(url)
    private val readId = scopedSourceReadId(url, hash)
    
    private val sourceRead = SourceRead(
        id = readId,
        url = url,
        canonicalUrl = url,
        documentId = docId,
        contentHash = hash,
        httpCode = 200,
        contentType = "text/plain",
        content = content,
        sourceRole = "research",
        authorityScore = 10,
        provenance = SourceReadProvenance.VERIFIED_FETCH
    )

    private fun createClaim(text: String, support: AgentClaimSupport = AgentClaimSupport.UNSUPPORTED, bindings: List<CitationBinding> = emptyList()): AgentClaim {
        val fingerprint = FingerprintUtils.calculateClaimFingerprint(taskId, AgentClaimType.FACT, text)
        return AgentClaim(
            id = scopedClaimId(fingerprint),
            taskId = taskId,
            text = text,
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = support,
            sourceUrls = listOf(url),
            citationBindings = bindings,
            claimFingerprint = fingerprint
        )
    }

    @Test
    fun `test policy rejects claim without source read`() {
        val claim = createClaim("Light travels fast.")
        val decision = FactualClaimSupportPolicy.evaluate(claim, emptyList())
        assertTrue(decision is FactualClaimSupportDecision.Unsupported)
    }

    @Test
    fun `test policy rejects tampered content hash`() {
        val tamperedRead = sourceRead.copy(content = "Tampered content", contentHash = hash) // Hash says it's original
        val binding = CitationBinding.createLegacy(
            claimId = "placeholder",
            sourceReadId = readId,
            documentId = docId,
            contentHash = hash,
            citationExcerpt = "speed of light",
            bindingMethod = CitationBindingMethod.EXACT
        )
        val claim = createClaim("Light speed").copy(id = "c1", citationBindings = listOf(binding.copy(claimId = "c1")))
        
        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(tamperedRead))
        assertTrue("Must fail if recomputed excerpt match fails on tampered content", decision is FactualClaimSupportDecision.Unsupported)
    }

    @Test
    fun `test policy rejects forged document ID`() {
        val binding = CitationBinding.createLegacy(
            claimId = "c1",
            sourceReadId = readId,
            documentId = "FORGED",
            contentHash = hash,
            citationExcerpt = "speed of light",
            bindingMethod = CitationBindingMethod.EXACT
        )
        val claim = createClaim("Light speed").copy(id = "c1", citationBindings = listOf(binding))
        
        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(sourceRead))
        assertTrue(decision is FactualClaimSupportDecision.Unsupported)
    }

    @Test
    fun `test exact matching returns EXACT method`() {
        val excerpt = "299,792,458 meters per second"
        val match = CitationValidator.containsExcerpt(content, excerpt)
        assertEquals(CitationBindingMethod.EXACT, match.bindingMethod)
        assertEquals(CitationValidator.MatchConfidence.EXACT, match.confidence)
        assertNotNull(match.passageStart)
        assertEquals(excerpt, content.substring(match.passageStart!!, match.passageEnd!!))
    }

    @Test
    fun `test case-insensitive matching returns CASE_INSENSITIVE method`() {
        val excerpt = "SPEED OF LIGHT"
        val match = CitationValidator.containsExcerpt(content, excerpt)
        assertEquals(CitationBindingMethod.CASE_INSENSITIVE, match.bindingMethod)
        assertEquals(CitationValidator.MatchConfidence.HIGH, match.confidence)
        assertEquals("speed of light", content.substring(match.passageStart!!, match.passageEnd!!).lowercase())
    }

    @Test
    fun `test token-normalized matching handles punctuation and whitespace`() {
        val excerpt = "speed of light... is approximately"
        val match = CitationValidator.containsExcerpt(content, excerpt)
        assertEquals(CitationBindingMethod.NORMALIZED_TOKEN_BOUNDARY, match.bindingMethod)
        assertTrue(match.confidence.isReliable())
        
        val passage = content.substring(match.passageStart!!, match.passageEnd!!)
        assertEquals("speed of light is approximately", passage.trim())
    }

    @Test
    fun `test re-verification detects changed citationExcerpt`() {
        // Original binding was for "speed of light"
        val binding = CitationBinding.createLegacy(
            claimId = "c1",
            sourceReadId = readId,
            documentId = docId,
            contentHash = hash,
            citationExcerpt = "Fabricated excerpt",
            bindingMethod = CitationBindingMethod.EXACT,
            passageStart = 4,
            passageEnd = 18, // "speed of light" range
            passageHash = FingerprintUtils.hash("speed of light")
        )
        val claim = createClaim("Light speed").copy(id = "c1", citationBindings = listOf(binding))
        
        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(sourceRead))
        assertTrue("Must fail if citationExcerpt doesn't match content even if range is valid", decision is FactualClaimSupportDecision.Unsupported)
    }

    @Test
    fun `test fingerprint-based reconciliation`() {
        val text = "Facts are immutable."
        val fp1 = FingerprintUtils.calculateClaimFingerprint("task-1", AgentClaimType.FACT, text)
        val fp2 = FingerprintUtils.calculateClaimFingerprint("task-1", AgentClaimType.FACT, text + " ") // extra space
        
        assertEquals("Fingerprint must be stable across whitespace", fp1, fp2)
        assertEquals("Durable ID must be stable", scopedClaimId(fp1), scopedClaimId(fp2))
    }

    @Test
    fun `test admissible provenance rules`() {
        val unverifiedRead = sourceRead.copy(id = "unv-1", provenance = SourceReadProvenance.UNVERIFIED_CITATION)
        val binding = CitationBinding.createLegacy(
            claimId = "c1",
            sourceReadId = "unv-1",
            documentId = docId,
            contentHash = hash,
            citationExcerpt = "speed of light",
            bindingMethod = CitationBindingMethod.EXACT,
            passageStart = 4,
            passageEnd = 18,
            passageHash = FingerprintUtils.hash("speed of light")
        )
        val claim = createClaim("Light speed").copy(id = "c1", citationBindings = listOf(binding))
        
        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(unverifiedRead))
        assertTrue("UNVERIFIED_CITATION cannot authorize factual support", decision is FactualClaimSupportDecision.Unsupported)
    }
}
