package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class ClaimSemanticsTest {

    @Test
    fun testScopedClaimIdStabilityWithTextFallback() {
        val taskId = "task-1"
        val text = "This is a stable claim."
        val fp1 = FingerprintUtils.calculateClaimFingerprint(taskId, AgentClaimType.FACT, text)
        val fp2 = FingerprintUtils.calculateClaimFingerprint(taskId, AgentClaimType.FACT, " " + text + "  ")
        assertEquals(fp1, fp2)
        val id1 = scopedClaimId(fp1)
        val id2 = scopedClaimId(fp2)
        assertEquals(id1, id2)
    }

    @Test
    fun testMergeClaimsReconcilesSupport() {
        val taskId = "task-1"
        val claimId = "id-1"
        val url = "https://example.com"
        val content = "text"
        val hash = FingerprintUtils.hash(content)
        val docId = scopedSourceDocumentId(url)
        val readId = scopedSourceReadId(url, hash)
        
        val existing = listOf(AgentClaim(id = claimId, taskId = taskId, text = content, type = AgentClaimType.FACT, confidence = 0.5, support = AgentClaimSupport.UNSUPPORTED, claimFingerprint = "fp1", sourceUrls = listOf(url)))
        val binding = CitationBinding.createLegacy(claimId = claimId, sourceReadId = readId, documentId = docId, contentHash = hash, citationExcerpt = content, passageStart = 0, passageEnd = 4, passageHash = FingerprintUtils.hash(content), bindingMethod = CitationBindingMethod.EXACT)
        val incoming = listOf(AgentClaim(id = claimId, taskId = taskId, text = content, type = AgentClaimType.FACT, confidence = 0.9, support = AgentClaimSupport.SUPPORTED, claimFingerprint = "fp1", sourceUrls = listOf(url), citationBindings = listOf(binding)))
        val read = SourceRead(id = readId, url = url, canonicalUrl = url, documentId = docId, contentHash = hash, httpCode = 200, contentType = "text/plain", content = content, sourceRole = "research", authorityScore = 10, provenance = SourceReadProvenance.VERIFIED_FETCH)
        
        val result = mergeClaims(existing, incoming, listOf(read))
        
        assertEquals(1, result.size)
        val claim = result.first()
        assertEquals(AgentClaimSupport.SUPPORTED, claim.support)
    }

    @Test
    fun testLegacyTestsRestored() {
        val task = AgentTask(id = "map_request", order = 0, title = "Map the request", instructions = "Inst", capability = AgentCapability.REASON)
        val claims = listOf(
            AgentClaim(id = "q", taskId = "map_request", text = "Question?", type = AgentClaimType.FACT, confidence = 1.0, support = AgentClaimSupport.UNSUPPORTED),
            AgentClaim(id = "f", taskId = "map_request", text = "Valid fact", type = AgentClaimType.FACT, confidence = 1.0, support = AgentClaimSupport.UNSUPPORTED)
        )
        val normalized = normalizeDurableClaims(task, claims)
        assertEquals(listOf("f"), normalized.map { it.id })
    }
}
