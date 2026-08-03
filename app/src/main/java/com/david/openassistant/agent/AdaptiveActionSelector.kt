package com.david.openassistant.agent

import java.util.UUID

data class ResearchAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    val target: String,
    val rationale: String,
    val relatedGapId: String? = null,
    val relatedEntityId: String? = null,
    val relatedHypothesisId: String? = null,
    val sourceFamily: String? = null,
    val expectedInformationGain: Double = 0.0,
    val priority: Double = 0.5
)

enum class ActionType {
    SEARCH_QUERY,
    FULL_PAGE_READ,
    CITATION_FOLLOW_UP,
    AUTHOR_FOLLOW_UP,
    ORGANIZATION_FOLLOW_UP,
    DATASET_LOOKUP,
    PRIMARY_RECORD_LOOKUP,
    ARCHIVED_PAGE_LOOKUP,
    ENTITY_DISAMBIGUATION,
    CONTRADICTION_SEARCH,
    TEMPORAL_SCOPE_SHIFT,
    GEOGRAPHIC_SCOPE_SHIFT,
    GAP_DECOMPOSITION,
    HYPOTHESIS_FALSIFICATION,
    CROSS_SOURCE_COMPARISON
}

object AdaptiveActionSelector {

    fun generateCandidates(goal: AgentGoal): List<ResearchAction> {
        val map = goal.investigationMap ?: return emptyList()
        val candidates = mutableListOf<ResearchAction>()

        // 1. Gaps -> Search or Decomposition
        map.gaps.filter { it.status == GapStatus.OPEN }.forEach { gap ->
            candidates.add(
                ResearchAction(
                    type = ActionType.SEARCH_QUERY,
                    target = gap.description,
                    rationale = "Resolve material gap: ${gap.description}",
                    relatedGapId = gap.id,
                    expectedInformationGain = 0.8
                )
            )
            if (gap.attemptsMade >= 3) {
                candidates.add(
                    ResearchAction(
                        type = ActionType.GAP_DECOMPOSITION,
                        target = gap.description,
                        rationale = "Decompose stagnant gap: ${gap.description}",
                        relatedGapId = gap.id,
                        expectedInformationGain = 0.7
                    )
                )
            }
        }

        // 2. Ambiguous Entities -> Disambiguation
        map.entities.filter { it.disambiguationStatus == DisambiguationStatus.AMBIGUOUS }.forEach { entity ->
            candidates.add(
                ResearchAction(
                    type = ActionType.ENTITY_DISAMBIGUATION,
                    target = entity.canonicalName,
                    rationale = "Resolve ambiguity for entity: ${entity.canonicalName}",
                    relatedEntityId = entity.id,
                    expectedInformationGain = 0.9
                )
            )
        }

        // 3. Hypotheses -> Falsification or Search
        map.hypotheses.filter { it.status == HypothesisStatus.PROPOSED }.forEach { hypothesis ->
            candidates.add(
                ResearchAction(
                    type = ActionType.HYPOTHESIS_FALSIFICATION,
                    target = hypothesis.statement,
                    rationale = "Test hypothesis: ${hypothesis.statement}",
                    relatedHypothesisId = hypothesis.id,
                    expectedInformationGain = 0.6
                )
            )
        }

        // 4. Source Targets -> Full Reads
        map.sourceTargets.filter { it.previousAttempts == 0 }.forEach { target ->
            candidates.add(
                ResearchAction(
                    type = ActionType.FULL_PAGE_READ,
                    target = target.targetIdentity,
                    rationale = "Full-read required for authoritative source: ${target.targetIdentity}",
                    sourceFamily = target.sourceFamily,
                    expectedInformationGain = 0.85
                )
            )
        }

        return candidates
    }

    fun scoreAndSelect(candidates: List<ResearchAction>, goal: AgentGoal): ResearchAction? {
        if (candidates.isEmpty()) return null
        
        // Deterministic scoring based on information gain, novelty, and objective relevance
        return candidates.maxByOrNull { action ->
            var score = action.expectedInformationGain
            
            // Penalty for repeated queries (simplified check)
            val isRepeated = goal.investigationMap?.queryOutcomes?.any { it.canonicalQuery == action.target } == true
            if (isRepeated) score -= 0.5
            
            // Bonus for high priority targets or entities
            if (action.type == ActionType.ENTITY_DISAMBIGUATION) score += 0.2
            if (action.type == ActionType.PRIMARY_RECORD_LOOKUP) score += 0.3
            
            score
        }
    }
}
