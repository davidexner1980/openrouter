package com.david.openassistant.agent

import java.util.Locale

/**
 * Centralizes the extraction, normalization, and attribution of research claims.
 * Complies with Factual Research Quality (Phase 6).
 */
object AgentClaimExtractor {

    /**
     * Attaches claims to evidence and re-validates their support status against source reads.
     */
    fun attachClaimsToEvidence(
        claims: List<AgentClaim>,
        evidenceItem: AgentEvidence,
        priorEvidence: List<AgentEvidence>,
        sourceReads: List<SourceRead>,
    ): List<AgentClaim> {
        val allEvidence = priorEvidence + evidenceItem
        val validEvidenceIds = allEvidence.mapTo(mutableSetOf()) { it.id }
        val evidenceIdsBySourceUrl = buildMap {
            allEvidence.forEach { evidence ->
                evidence.sources.forEach { source ->
                    getOrPut(source.url) { mutableSetOf() }.add(evidence.id)
                }
            }
        }
        
        return claims.map { claim ->
            val explicitlyReferencedEvidenceIds = claim.supportingEvidenceIds.filter(validEvidenceIds::contains)
            val evidenceIds = buildList {
                addAll(explicitlyReferencedEvidenceIds)
                claim.sourceUrls.forEach { sourceUrl ->
                    addAll(evidenceIdsBySourceUrl[sourceUrl].orEmpty())
                }
                if (
                    claim.type != AgentClaimType.FACT ||
                    evidenceItem.sources.isEmpty() ||
                    evidenceItem.sources.any { it.url in claim.sourceUrls }
                ) {
                    add(evidenceItem.id)
                }
            }.distinct()

            val resolvedSourceUrls = resolvePreciseClaimSourceUrls(
                explicitSourceUrls = claim.sourceUrls,
                referencedEvidenceIds = explicitlyReferencedEvidenceIds,
                evidence = allEvidence,
            )
            
            val baseClaim = claim.copy(
                supportingEvidenceIds = evidenceIds,
                sourceUrls = resolvedSourceUrls
            )
            
            // Phase 6: SUPPORTED status must not be blindly trusted; re-validate against snapshots.
            val decision = FactualClaimSupportPolicy.evaluate(baseClaim, sourceReads)
            val support = when (decision) {
                is FactualClaimSupportDecision.Supported -> AgentClaimSupport.SUPPORTED
                is FactualClaimSupportDecision.PartiallyBound -> AgentClaimSupport.PARTIAL
                is FactualClaimSupportDecision.Contradicted -> AgentClaimSupport.CONTRADICTED
                is FactualClaimSupportDecision.Unsupported -> AgentClaimSupport.UNSUPPORTED
            }

            repairOverAttributedClaim(
                claim = baseClaim.copy(
                    support = support,
                    citationBindings = when (decision) {
                        is FactualClaimSupportDecision.Supported -> decision.validBindings
                        is FactualClaimSupportDecision.PartiallyBound -> decision.validBindings
                        else -> emptyList()
                    }
                ),
                evidence = allEvidence,
            )
        }
    }

    /**
     * Normalizes claim text for comparison and fingerprinting.
     */
    fun String.normalizedClaimText(): String {
        return this.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
