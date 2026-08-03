package com.david.openassistant.agent

import java.util.UUID

object InvestigationMapLogic {

    fun updateWithEvidence(
        map: InvestigationMap,
        evidence: List<AgentEvidence>,
        claims: List<AgentClaim>
    ): InvestigationMap {
        // In a real implementation, this would use a model to extract entities and gaps.
        // For now, we provide the infrastructure to hold these updates.
        
        val updatedHypotheses = map.hypotheses.map { hypothesis ->
            val supporting = claims.filter { it.support == AgentClaimSupport.SUPPORTED && it.text.contains(hypothesis.statement, ignoreCase = true) }
            val contradicting = claims.filter { it.support == AgentClaimSupport.CONTRADICTED && it.text.contains(hypothesis.statement, ignoreCase = true) }
            
            if (supporting.isNotEmpty() || contradicting.isNotEmpty()) {
                hypothesis.copy(
                    supportingEvidenceIds = (hypothesis.supportingEvidenceIds + supporting.map { it.id }).distinct(),
                    contradictingEvidenceIds = (hypothesis.contradictingEvidenceIds + contradicting.map { it.id }).distinct(),
                    status = when {
                        contradicting.isNotEmpty() -> HypothesisStatus.CONTRADICTED
                        supporting.isNotEmpty() -> HypothesisStatus.SUPPORTED
                        else -> hypothesis.status
                    },
                    lastTestedAt = System.currentTimeMillis()
                )
            } else {
                hypothesis
            }
        }

        return map.copy(
            hypotheses = updatedHypotheses,
            lastCheckpointAt = System.currentTimeMillis()
        )
    }

    fun resolveEntity(map: InvestigationMap, entityId: String, acceptedIdentity: String, rejectedIdentities: List<String>): InvestigationMap {
        return map.copy(
            entities = map.entities.map { entity ->
                if (entity.id == entityId) {
                    entity.copy(
                        canonicalName = acceptedIdentity,
                        disambiguationStatus = DisambiguationStatus.RESOLVED,
                        rejectedInterpretations = (entity.rejectedInterpretations + rejectedIdentities).distinct(),
                        confidence = 0.9,
                        lastUpdatedAt = System.currentTimeMillis()
                    )
                } else entity
            }
        )
    }

    fun addGap(map: InvestigationMap, gap: InformationGap): InvestigationMap {
        if (map.gaps.any { it.description == gap.description }) return map
        return map.copy(gaps = map.gaps + gap)
    }

    fun resolveGap(map: InvestigationMap, gapId: String, evidenceIds: List<String>): InvestigationMap {
        return map.copy(
            gaps = map.gaps.map { gap ->
                if (gap.id == gapId) {
                    gap.copy(
                        status = GapStatus.RESOLVED,
                        resolutionEvidenceIds = (gap.resolutionEvidenceIds + evidenceIds).distinct()
                    )
                } else gap
            }
        )
    }

    fun recordQueryOutcome(map: InvestigationMap, outcome: QueryOutcome): InvestigationMap {
        // Bounded collection: keep last 100 query outcomes
        val updatedOutcomes = (map.queryOutcomes + outcome).takeLast(100)
        return map.copy(queryOutcomes = updatedOutcomes)
    }

    fun extractCitationFollowUps(map: InvestigationMap, result: AgentStepResult): InvestigationMap {
        val newTargets = result.newCitations.map { citation ->
            SourceTarget(
                sourceFamily = "Citation",
                targetIdentity = citation,
                rationale = "Citation discovered in substantive source.",
                priority = 0.6
            )
        }
        // Bounded: keep max 50 source targets
        val updatedTargets = (map.sourceTargets + newTargets).distinctBy { it.targetIdentity }.takeLast(50)
        return map.copy(sourceTargets = updatedTargets)
    }
}
