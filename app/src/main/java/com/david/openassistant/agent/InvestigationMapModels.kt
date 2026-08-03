package com.david.openassistant.agent

import java.util.UUID

data class InvestigationMap(
    val entities: List<EntityRecord> = emptyList(),
    val gaps: List<InformationGap> = emptyList(),
    val hypotheses: List<Hypothesis> = emptyList(),
    val sourceTargets: List<SourceTarget> = emptyList(),
    val queryOutcomes: List<QueryOutcome> = emptyList(),
    val lastCheckpointAt: Long = System.currentTimeMillis()
)

data class EntityRecord(
    val id: String = UUID.randomUUID().toString(),
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val type: String?, // e.g., Person, Organization, Product, Version, Event
    val parentOrganizations: List<String> = emptyList(),
    val productRelationships: List<String> = emptyList(),
    val versionRelationships: List<String> = emptyList(),
    val geographicScope: String? = null,
    val temporalValidity: String? = null,
    val disambiguationStatus: DisambiguationStatus = DisambiguationStatus.AMBIGUOUS,
    val supportingEvidenceIds: List<String> = emptyList(),
    val rejectedInterpretations: List<String> = emptyList(),
    val confidence: Double = 0.5,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

enum class DisambiguationStatus {
    AMBIGUOUS,
    CANDIDATES_IDENTIFIED,
    RESOLVED,
    UNRESOLVABLE,
    REJECTED
}

data class InformationGap(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val impactOnFinalAnswer: String,
    val relatedAcceptanceCriterionId: String? = null,
    val relatedEntityIds: List<String> = emptyList(),
    val requiredEvidenceType: String? = null,
    val preferredSourceFamilies: List<String> = emptyList(),
    val answerChangingThreshold: String? = null,
    val status: GapStatus = GapStatus.OPEN,
    val attemptsMade: Int = 0,
    val blockingReason: String? = null,
    val resolutionEvidenceIds: List<String> = emptyList()
)

enum class GapStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    PARTIALLY_RESOLVED,
    BLOCKED,
    DEPRECATED
}

data class Hypothesis(
    val id: String = UUID.randomUUID().toString(),
    val statement: String,
    val relatedGapId: String?,
    val supportingEvidenceIds: List<String> = emptyList(),
    val contradictingEvidenceIds: List<String> = emptyList(),
    val falsifiers: List<String> = emptyList(),
    val confidence: Double = 0.5,
    val status: HypothesisStatus = HypothesisStatus.PROPOSED,
    val lastTestedAt: Long? = null
)

enum class HypothesisStatus {
    PROPOSED,
    SUPPORTED,
    WEAKENED,
    CONTRADICTED,
    UNRESOLVED,
    SUPERSEDED
}

data class SourceTarget(
    val id: String = UUID.randomUUID().toString(),
    val sourceFamily: String, // Official, Academic, Legal, Code, etc.
    val targetIdentity: String, // URL, citation string, etc.
    val rationale: String? = null,
    val expectedEvidence: String? = null,
    val authorityExpectation: Double = 0.5,
    val independenceGroupId: String? = null,
    val accessStatus: String? = null,
    val previousAttempts: Int = 0,
    val followUpLinks: List<String> = emptyList(),
    val priority: Double = 0.5
)

data class QueryOutcome(
    val id: String = UUID.randomUUID().toString(),
    val canonicalQuery: String,
    val purpose: String?,
    val relatedGapId: String?,
    val relatedHypothesisId: String?,
    val sourceFamily: String?,
    val resultDomains: List<String> = emptyList(),
    val usefulSourceIds: List<String> = emptyList(),
    val newEntitiesDiscovered: List<String> = emptyList(),
    val newCitationsDiscovered: List<String> = emptyList(),
    val newGapsCreated: List<String> = emptyList(),
    val evidenceAcceptedIds: List<String> = emptyList(),
    val utilityRationale: String?,
    val executionTimestamp: Long = System.currentTimeMillis(),
    val cycleId: String?,
    val tacticId: String?
)
