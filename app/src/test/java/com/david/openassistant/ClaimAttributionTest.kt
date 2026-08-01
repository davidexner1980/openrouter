package com.david.openassistant

import com.david.openassistant.agent.AgentEvidence
import com.david.openassistant.agent.AgentEvidenceKind
import com.david.openassistant.agent.AgentClaim
import com.david.openassistant.agent.AgentClaimSupport
import com.david.openassistant.agent.AgentClaimType
import com.david.openassistant.agent.AgentSourceCitation
import com.david.openassistant.agent.repairOverAttributedClaim
import com.david.openassistant.agent.resolvePreciseClaimSourceUrls
import com.david.openassistant.agent.findImpreciseClaimSourceSelections
import com.david.openassistant.agent.refineImpreciseClaimSourceSelections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimAttributionTest {
    @Test
    fun multiSourceBundleDoesNotMakeEveryUrlSupportOneClaim() {
        val evidence = evidence(
            id = "bundle",
            urls = listOf("https://one.example/a", "https://two.example/b"),
        )

        val urls = resolvePreciseClaimSourceUrls(
            explicitSourceUrls = emptyList(),
            referencedEvidenceIds = listOf("bundle"),
            evidence = listOf(evidence),
        )

        assertEquals(emptyList<String>(), urls)
    }

    @Test
    fun explicitUrlAndSingleSourceEvidenceRemainPreciselyAttributed() {
        val evidence = evidence("single", listOf("https://official.example/item"))

        val urls = resolvePreciseClaimSourceUrls(
            explicitSourceUrls = listOf("https://direct.example/fact"),
            referencedEvidenceIds = listOf("single"),
            evidence = listOf(evidence),
        )

        assertEquals(
            listOf("https://direct.example/fact", "https://official.example/item"),
            urls,
        )
    }

    @Test
    fun legacyBundleFanOutIsPrunedAndFactBecomesPartial() {
        val evidence = evidence(
            id = "large-bundle",
            urls = (1..12).map { "https://source$it.example/item" },
        )
        val claim = AgentClaim(
            taskId = "research",
            text = "A factual finding with no direct claim-level URL.",
            type = AgentClaimType.FACT,
            confidence = 0.9,
            support = AgentClaimSupport.SUPPORTED,
            supportingEvidenceIds = listOf(evidence.id),
            sourceUrls = evidence.sources.map { it.url },
        )

        val repaired = repairOverAttributedClaim(claim, listOf(evidence))

        assertEquals(emptyList<String>(), repaired.sourceUrls)
        assertEquals(AgentClaimSupport.PARTIAL, repaired.support)
    }

    @Test
    fun siblingProductPageIsRejectedWhenAnotherBundledPageMatchesTheClaim() {
        val grizzly = "https://www.beararchery.com/products/bear-archery-grizzly-recurve-bow"
        val superKodiak = "https://www.beararchery.com/products/bear-archery-super-kodiak-recurve-bow"
        val evidence = AgentEvidence(
            id = "bear-products",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = "Bear product comparison",
            summary = "Compared product pages",
            content = "The bundle preserves separate pages for separate models.",
            sources = listOf(
                AgentSourceCitation("Grizzly Recurve Bow", grizzly),
                AgentSourceCitation("Super Kodiak Recurve Bow", superKodiak),
            ),
        )
        val claim = AgentClaim(
            id = "super-kodiak-fact",
            taskId = "synthesis",
            text = "The Bear Super Kodiak is the selected one-piece hunting recurve.",
            type = AgentClaimType.FACT,
            confidence = 0.9,
            support = AgentClaimSupport.SUPPORTED,
            supportingEvidenceIds = listOf(evidence.id),
            sourceUrls = listOf(grizzly),
        )

        val issues = findImpreciseClaimSourceSelections(listOf(claim), listOf(evidence))

        assertEquals(1, issues.size)
        assertEquals(grizzly, issues.single().citedUrl)
        assertEquals(superKodiak, issues.single().betterMatchingUrl)
        assertTrue(
            findImpreciseClaimSourceSelections(
                listOf(claim.copy(sourceUrls = listOf(superKodiak))),
                listOf(evidence),
            ).isEmpty(),
        )

        val refined = refineImpreciseClaimSourceSelections(listOf(claim), listOf(evidence)).single()
        assertEquals(listOf(superKodiak), refined.sourceUrls)
        assertEquals(claim.supportingEvidenceIds, refined.supportingEvidenceIds)
        assertEquals(claim.support, refined.support)
    }

    private fun evidence(id: String, urls: List<String>) = AgentEvidence(
        id = id,
        kind = AgentEvidenceKind.DEEP_RESEARCH,
        title = "Research bundle",
        summary = "Sources",
        content = "Analyzed evidence",
        sources = urls.map { AgentSourceCitation(it, it) },
    )
}
