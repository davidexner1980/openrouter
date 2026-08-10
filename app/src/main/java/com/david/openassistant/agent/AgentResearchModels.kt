package com.david.openassistant.agent

import java.util.UUID

/**
 * Represents an immutable snapshot of a research source at a specific point in time.
 * Complies with the Immutable Source-Read Law.
 */
interface SourceSnapshot {
    val canonicalUrl: String
    val contentHash: String
    val retrievedAt: Long
    val content: String
    val isPublicationGrade: Boolean
}

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
    val remainingReadGap: Int,
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
    val remainingReadGap: Int,
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
    STRATEGY_EXHAUSTED,
    FAILED_RETRYABLE,
    RECONCILIATION_REQUIRED,
    ALTERNATE_STRATEGY_REQUIRED,
    FAILED_NEEDS_ACTION;

    fun isTerminal(): Boolean = this in setOf(
        COMMITTED,
        REJECTED_NOT_NOVEL,
        STRATEGY_EXHAUSTED,
        ALTERNATE_STRATEGY_REQUIRED,
        FAILED_NEEDS_ACTION
    )

    fun isNonTerminal(): Boolean = !isTerminal()

    fun canTransitionTo(next: RecoveryPlanStatus): Boolean = when (this) {
        PREPARED -> next == GENERATING
        GENERATING -> next in setOf(
            READY_TO_COMMIT,
            REJECTED_NOT_NOVEL,
            FAILED_RETRYABLE,
            RECONCILIATION_REQUIRED,
            ALTERNATE_STRATEGY_REQUIRED,
            FAILED_NEEDS_ACTION
        )
        READY_TO_COMMIT -> next in setOf(COMMITTED, REJECTED_NOT_NOVEL, STRATEGY_EXHAUSTED)
        FAILED_RETRYABLE -> next in setOf(GENERATING, RECONCILIATION_REQUIRED, ALTERNATE_STRATEGY_REQUIRED)
        RECONCILIATION_REQUIRED -> next in setOf(GENERATING, ALTERNATE_STRATEGY_REQUIRED, FAILED_NEEDS_ACTION)
        ALTERNATE_STRATEGY_REQUIRED -> false
        else -> false
    }
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
    val inputObjectiveFingerprint: String = "", // V43
    val triggerExecutionFingerprint: String = "", // V43
    val version: Int = 2, // V43 Stabilized
    val diagnosis: ExecutionStallDiagnosis,
    val selectedTactic: EscalationTactic,
    val status: RecoveryPlanStatus,
    val logicalProviderRequestId: String?,
    val proposal: RecoveryProposal?,
    val proposalFingerprint: String?,
    val validationResult: String?,
    val failureClassification: String?,
    val failureMessage: String?,
    val accountingSummary: AgentApiSummary? = null, // V42.4
    val retryAuthorizedFingerprint: String? = null, // V42.4
    val createdAt: Long = System.currentTimeMillis(),
    val generatedAt: Long? = null,
    val committedAt: Long? = null
)

enum class ResearchCycleStatus {
    PLANNING,
    ACTIVE,
    SUPERSEDED,
    COMPLETED,
    EXHAUSTED,
    FAILED
}

data class ResearchCycleLearningSummary(
    val establishedFindings: List<String>,
    val acceptedEvidenceIds: List<String>,
    val acceptedClaimIds: List<String>,
    val remainingUnresolvedGaps: List<String>,
    val contradictions: List<String>,
    val rejectedOrUnreliableMaterial: List<String>,
    val exhaustedQueryApproaches: List<String>,
    val exhaustedSourceFamilies: List<String>,
    val attemptedTactics: List<EscalationTactic>,
    val failedStrategyFingerprints: List<String>,
    val carryForwardEvidenceIds: List<String>,
    val advancementReason: String
)

data class ResearchCycle(
    val id: String, // Deterministic ID
    val ordinal: Int,
    val parentCycleId: String?,
    val status: ResearchCycleStatus,
    val objectiveRevisionId: String,
    val triggerDiagnosis: ExecutionStallDiagnosis,
    val selectedAdvancementTactic: EscalationTactic,
    val strategyFingerprint: String,
    val queryPortfolioFingerprint: String,
    val acceptedEvidenceFingerprint: String,
    val unresolvedGapFingerprint: String,
    val learningSummary: ResearchCycleLearningSummary?,
    val createdAt: Long = System.currentTimeMillis(),
    val activatedAt: Long? = null,
    val supersededAt: Long? = null,
    val completedAt: Long? = null,
    val exhaustedAt: Long? = null
)

data class ObjectiveRevision(
    val id: String, // Deterministic ID
    val ordinal: Int,
    val parentRevisionId: String?,
    val immutableRootObjectiveFingerprint: String,
    val operationalObjective: String,
    val unresolvedGaps: List<String>,
    val retainedConstraints: List<String>,
    val evidenceRequirements: List<String>,
    val revisionReason: String,
    val revisionFingerprint: String,
    val createdAt: Long = System.currentTimeMillis()
)

sealed interface RecoveryProposalGenerationResult {
    data class ProposalAvailable(
        val proposal: RecoveryProposal,
        val summary: AgentApiSummary?,
        val exchangeId: String,
        val reusedDurableResult: Boolean,
    ) : RecoveryProposalGenerationResult

    data class RetryableTransportFailure(
        val descriptor: FailureDescriptor,
        val attempt: ProviderRequestAttempt,
    ) : RecoveryProposalGenerationResult

    data class ReconciliationRequired(
        val attempt: ProviderRequestAttempt,
        val kind: ProviderReconciliationFailureKind,
        val reason: String,
    ) : RecoveryProposalGenerationResult

    data class AlternateStrategyRequired(
        val kind: ProviderReconciliationFailureKind?,
        val reason: String,
        val failedAttempt: ProviderRequestAttempt?,
    ) : RecoveryProposalGenerationResult

    data class NeedsUserAction(
        val kind: ProviderReconciliationFailureKind,
        val reason: String,
    ) : RecoveryProposalGenerationResult

    data class StorageFailure(
        val cause: Throwable,
    ) : RecoveryProposalGenerationResult
}

sealed interface RecoveryPlanTransitionResult {
    data class Committed(
        val goal: AgentGoal,
        val plan: ResearchRecoveryPlan,
    ) : RecoveryPlanTransitionResult

    data class AlreadyAtTarget(
        val goal: AgentGoal,
        val plan: ResearchRecoveryPlan,
    ) : RecoveryPlanTransitionResult

    data class StatusMismatch(
        val expected: RecoveryPlanStatus,
        val actual: RecoveryPlanStatus,
    ) : RecoveryPlanTransitionResult

    data class IllegalTransition(
        val from: RecoveryPlanStatus,
        val to: RecoveryPlanStatus,
    ) : RecoveryPlanTransitionResult

    data class InputFingerprintMismatch(
        val expected: String,
        val actual: String,
    ) : RecoveryPlanTransitionResult

    data class ProposalIdentityMismatch(
        val expected: String?,
        val actual: String?,
    ) : RecoveryPlanTransitionResult

    data class LogicalRequestMismatch(
        val expected: String?,
        val actual: String?,
    ) : RecoveryPlanTransitionResult

    object OwnershipRejected : RecoveryPlanTransitionResult
    object GoalMissing : RecoveryPlanTransitionResult
    object PlanMissing : RecoveryPlanTransitionResult
    object GoalTerminal : RecoveryPlanTransitionResult
    data class StorageFailure(val cause: Throwable) : RecoveryPlanTransitionResult
}

enum class SourceFetchStatus {
    CLAIMED,
    DISPATCH_STARTED,
    DELIVERY_AMBIGUOUS,
    RESULT_RECEIVED,
    SOURCE_READ_COMMITTED,
    TERMINAL_FAILURE
}

enum class SourceFetchTransportStage {
    NOT_DISPATCHED,
    DNS_RESOLVED,
    CONNECTION_ESTABLISHED,
    REQUEST_SENT,
    RESPONSE_HEADERS_RECEIVED,
    RESPONSE_BODY_RECEIVED
}

data class SourceFetchAttempt(
    val id: String,
    val logicalFetchId: String,
    val goalId: String,
    val taskId: String,
    val canonicalUrl: String,
    val fetchFingerprint: String,
    val attemptOrdinal: Int,
    val executionGeneration: Int,
    val status: SourceFetchStatus,
    val transportStage: SourceFetchTransportStage,
    val deliveryCertainty: ProviderDeliveryCertainty,
    val retryAuthorizationFingerprint: String?,
    val sourceReadId: String?,
    val failureClassification: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
