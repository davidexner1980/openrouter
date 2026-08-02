package com.david.openassistant.agent

import java.util.UUID

data class ResearchAllocationProfile(
    val complexity: ResearchComplexity = ResearchComplexity.MEDIUM,
    val risk: ResearchRisk = ResearchRisk.LOW,
    val freshnessNeed: FreshnessNeed = FreshnessNeed.USEFUL,
    val sourceStrictness: SourceStrictness = SourceStrictness.NORMAL,
    val contradictionNeed: ContradictionNeed = ContradictionNeed.NORMAL,
    val targetResearchPasses: Int = 3,
    val targetDistinctSources: Int = 6,
    val targetDomains: Int = 3,
    val targetSearchQueriesPerPass: Int = 3,
    val targetFullReadsPerPass: Int = 2,
    val maxRabbitHoleIterations: Int = 2,
    val maxLocalAttemptsPerTask: Int = 3,
    val synthesisModelStrength: ModelStrength = ModelStrength.NORMAL,
    val explanation: String = "Balanced allocation for standard research.",
)

data class ResearchAllocationGaps(
    val goalId: String,
    val profile: ResearchAllocationProfile,
    val remainingSourceGap: Int,
    val remainingDomainGap: Int,
    val remainingPrimarySourceGap: Boolean,
    val remainingContradictionGap: Boolean,
    val remainingGapClosureGap: Boolean,
    val estimatedEffortLabel: String,
)

data class AllocatedTaskSelection(
    val taskId: String?,
    val reason: String,
    val retryAfterCooldown: Boolean = false,
)

data class ResearchTaskBudget(
    val searchQueriesTarget: Int,
    val fullReadsTarget: Int,
    val distinctSourcesTarget: Int,
    val novelSourcesTarget: Int,
    val minFactClaims: Int,
    val maxRabbitHoleIterations: Int,
    val allowModelEscalation: Boolean,
    val forcedTactic: EscalationTactic = EscalationTactic.NONE,
)

data class ResearchAllocationSnapshot(
    val profile: ResearchAllocationProfile,
    val currentTaskId: String?,
    val currentTaskReason: String?,
    val remainingSourceGap: Int,
    val remainingDomainGap: Int,
    val remainingPrimarySourceGap: Boolean,
    val remainingContradictionGap: Boolean,
    val estimatedEffortLabel: String,
    val lastAllocationReason: String?,
)

data class AgentPlanDraft(
    val title: String,
    val objective: String,
    val finalOutputDescription: String,
    val acceptanceCriteria: List<AgentAcceptanceCriterion>,
    val tasks: List<AgentTaskDraft>,
    val objectiveContract: ObjectiveContract? = null,
)

data class AgentTaskDraft(
    val id: String,
    val title: String,
    val instructions: String,
    val capability: AgentCapability,
    val dependsOn: List<String>,
    val weight: Double,
    val acceptanceCriteria: List<AgentAcceptanceCriterion>,
)

data class RejectedResearchQuery(
    val originalQuery: String,
    val normalizedQuery: String,
    val canonicalFingerprint: String,
    val taskId: String,
    val reasonCode: String,
    val reasonDetail: String,
    val matchedWeakAnchors: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val generation: Int,
)

enum class RecoveryPlanStatus {
    PREPARED,
    GENERATING,
    READY_TO_COMMIT,
    COMMITTED,
    REJECTED_NOT_NOVEL,
    FAILED_RETRYABLE,
    FAILED_NEEDS_ACTION
}

data class RecoveryProposal(
    val revisedInvestigationInterpretation: String,
    val specificUnresolvedGap: String,
    val selectedSourceFamilyShift: String?,
    val evidenceTargets: List<String>,
    val falsifiers: List<String>,
    val newQueryPortfolio: List<String>,
    val followUpRule: String?,
    val rationale: String,
    val expectedNoveltyDimensions: List<String>
)

data class ResearchRecoveryPlan(
    val id: String, // Deterministic SHA-256
    val goalId: String,
    val taskId: String,
    val inputExecutionFingerprint: String,
    val diagnosis: ExecutionStallDiagnosis,
    val selectedTactic: EscalationTactic,
    val status: RecoveryPlanStatus,
    val logicalProviderRequestId: String?,
    val proposal: RecoveryProposal?,
    val proposalFingerprint: String?,
    val validationResult: String?,
    val failureClassification: String?,
    val failureMessage: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val generatedAt: Long? = null,
    val committedAt: Long? = null
)
