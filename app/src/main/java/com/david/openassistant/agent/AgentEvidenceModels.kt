package com.david.openassistant.agent

import java.util.UUID

data class AgentSourceCitation(
    val title: String,
    val url: String,
    val excerpt: String? = null,
)

data class AgentEvidence(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String? = null,
    val cycleId: String? = null,
    val kind: AgentEvidenceKind,
    val title: String,
    val summary: String,
    val content: String,
    val sources: List<AgentSourceCitation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

data class AgentClaim(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val text: String,
    val type: AgentClaimType,
    val confidence: Double,
    val support: AgentClaimSupport,
    val supportingEvidenceIds: List<String> = emptyList(),
    val sourceUrls: List<String> = emptyList(),
    val reviewExplanation: String? = null,
) {
    val isSpeculative: Boolean
        get() = type == AgentClaimType.ORIGINAL_HYPOTHESIS
}

data class AgentEvidenceLink(
    val id: String = UUID.randomUUID().toString(),
    val claimId: String,
    val evidenceId: String,
    val relation: AgentEvidenceRelation,
    val explanation: String? = null,
)

data class AgentCheckpoint(
    val id: String = UUID.randomUUID().toString(),
    val sequence: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val completedTaskIds: List<String>,
    val progressScore: Double,
    val note: String,
)

data class AgentEvent(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val message: String,
)

data class AgentConceptCandidate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val definition: String,
    val triggerPattern: String,
    val expectedBenefit: String,
    val risks: List<String>,
    val validationTests: List<String>,
    val status: AgentConceptStatus = AgentConceptStatus.PROPOSED,
    val createdAt: Long = System.currentTimeMillis(),
)

data class BlockedSourceRecord(
    val canonicalDocumentId: String?,
    val canonicalUrl: String,
    val routeKind: String,
    val failureClass: String,
    val firstFailedAt: Long = System.currentTimeMillis(),
    val lastFailedAt: Long = System.currentTimeMillis(),
    val lastFailureDetailCode: String? = null,
    val attemptCount: Int = 1,
    val alternateRoutesAttempted: List<String> = emptyList(),
    val terminalState: Boolean = false,
    val sourceTaskId: String? = null,
)
