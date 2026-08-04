package com.david.openassistant.agent

import java.util.UUID

enum class ProviderTransportStage {
    NOT_DISPATCHED,
    CONNECTING,
    REQUEST_HEADERS_SENT,
    REQUEST_BODY_STARTED,
    REQUEST_BODY_COMPLETE,
    RESPONSE_HEADERS_RECEIVED,
    RESPONSE_BODY_READING,
    RESPONSE_BODY_COMPLETE,
    PARSING,
    CLASSIFYING,
    TERMINAL
}

enum class ProviderDeliveryCertainty {
    NOT_SENT,
    SENT_UNCONFIRMED,
    RESPONSE_CONFIRMED
}

enum class ProviderAttemptFailureClass {
    LOCAL_VALIDATION,
    DNS_FAILURE,
    CONNECT_FAILURE,
    TLS_FAILURE,
    WRITE_TIMEOUT,
    CALL_TIMEOUT,
    READ_TIMEOUT_BEFORE_HEADERS,
    RESPONSE_BODY_TIMEOUT,
    CONNECTION_RESET,
    HTTP_400_MODEL_LIST,
    HTTP_408,
    HTTP_429,
    HTTP_5XX,
    MALFORMED_RESPONSE,
    STRUCTURED_RESPONSE_DEFICIT,
    CANCELLED,
    CANCELLATION_TIMEOUT,
    TERMINAL_PERSISTENCE_FAILURE,
    UNKNOWN_TRANSPORT_FAILURE
}

enum class ProviderRetryDecision {
    NO_RETRY,
    LOCAL_REPAIR_ONLY,
    SAFE_WIRE_RETRY,
    BOUNDED_AMBIGUOUS_RETRY,
    WAIT_RETRY_AFTER,
    ROUTE_RECOVERY,
    PAUSE_AMBIGUOUS_SETTLEMENT
}

data class ProviderRetryAuthorization(
    val logicalRequestId: String,
    val payloadFingerprint: String,
    val executionGeneration: Int,
    val previousExchangeId: String?,
    val failureClass: String,
    val deliveryCertainty: ProviderDeliveryCertainty,
    val attemptOrdinal: Int,
    val authorizationTimestamp: Long = System.currentTimeMillis()
)

data class ProviderRequestAttempt(
    val exchangeId: String,
    val logicalRequestId: String = "",
    val wireAttemptOrdinal: Int = 1,
    val previousExchangeId: String? = null,
    val providerResponseId: String? = null,
    val transportStage: ProviderTransportStage = ProviderTransportStage.NOT_DISPATCHED,
    val deliveryCertainty: ProviderDeliveryCertainty = ProviderDeliveryCertainty.NOT_SENT,
    val parentOperationId: String,
    val goalId: String,
    val taskId: String? = null,
    val executionGeneration: Int,
    val requestedModel: String,
    val resolvedModel: String? = null,
    val role: AgentTaskRole? = null,
    val payloadFingerprint: String,
    val exchangeOutcome: ExchangeOutcome = ExchangeOutcome.ACTIVE,
    val providerAccountingOutcome: ProviderAccountingOutcome = ProviderAccountingOutcome.PENDING,
    val domainCommitOutcome: MissionDomainCommitOutcome = MissionDomainCommitOutcome.PENDING,
    val usageSource: UsageSource? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val pricingModelId: String? = null,
    val httpStatusCode: Int? = null,
    val failureClass: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val reconciliationClaimOwner: String? = null,
    val reconciliationClaimedAt: Long? = null,
    val safeDiagnosticSummary: String? = null,
    val recoveryPlanId: String? = null,
    val responsePayloadFingerprint: String? = null,
)

data class RouteFailureFingerprint(
    val goalId: String,
    val taskId: String? = null,
    val operationId: String,
    val canonicalPayloadHash: String,
    val schemaVersion: Int = 1,
    val role: AgentTaskRole,
    val route: String,
    val resolvedModel: String? = null,
    val failureClass: String,
    val repairApplied: String? = null,
    val retryAfterMs: Long? = null,
    val nextEligibleTime: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

data class BodyBuilderProposalClaim(
    val claimId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val payloadFingerprint: String,
    val claimOwner: String,
    val executionGeneration: Int,
    val claimedAt: Long = System.currentTimeMillis(),
    val leaseExpiresAt: Long,
    val dispatchStatus: ProposalDispatchStatus = ProposalDispatchStatus.CLAIMED,
    val providerExchangeId: String? = null,
    val terminalProposalOutcome: String? = null,
    val acceptedFieldPaths: List<String> = emptyList(),
)

data class AgentAcceptanceCriterion(
    val id: String,
    val description: String,
    val weight: Double = 1.0,
)

data class AgentAcceptanceCheck(
    val criterionId: String,
    val status: AgentAcceptanceCheckStatus,
    val score: Double,
    val explanation: String,
)

/**
 * A persisted lease that prevents concurrent worker execution for the same goal.
 * Heartbeats allow a new worker to recover the lease if the previous one died
 * without releasing it.
 */
data class AgentExecutionLease(
    val workerId: String,
    val ownerProcessSessionId: String, // V36
    val taskId: String,
    val attemptId: String,
    val generation: Int,
    val acquiredAt: Long,
    val heartbeatAt: Long,
)

data class ExecutionOwnership(
    val workerId: String,
    val leaseAttemptId: String,
    val executionGeneration: Int,
    val taskId: String,
)

data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val cycleId: String? = null,
    val order: Int,
    val title: String,
    val instructions: String,
    val capability: AgentCapability,
    val dependsOn: List<String> = emptyList(),
    val status: AgentTaskStatus = AgentTaskStatus.PLANNED,
    val attemptCount: Int = 0,
    val lifetimeAttemptCount: Int = 0,
    val taskGeneration: Int = 0,
    val consecutiveNoProgressCount: Int = 0,
    val lastMaterialProgressAt: Long? = null,
    val lastMaterialProgressFingerprint: String? = null,
    val branchExhaustionReason: String? = null,
    val branchExhaustedAt: Long? = null,
    val lastError: String? = null,
    val weight: Double = 1.0,
    val automaticWindowReopenCount: Int = 0,
    val globalAutomaticWindowReopenCount: Int = 0,
    val lastRequestFingerprint: String? = null,
    val lastEscalatedFingerprint: String? = null,
    val progressFingerprint: String? = null,
    val queryFingerprints: List<String> = emptyList(),
    val recentQueryFingerprints: List<String> = emptyList(),
    val recentSourceFingerprints: List<String> = emptyList(),
    val recentClaimFingerprints: List<String> = emptyList(),
    val acceptanceCriteria: List<AgentAcceptanceCriterion> = emptyList(),
    val acceptanceChecks: List<AgentAcceptanceCheck> = emptyList(),
    val progressScore: Double = 0.0,
    val cooldownUntil: Long? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val outputEvidenceId: String? = null,
    val failureClass: String? = null,
    val waitReason: String? = null,
    val waitCondition: String? = null,
    val lastRecoveryStrategy: String? = null,
    val resultSetFingerprint: String? = null,
    val recoveryStrategyFingerprint: String? = null,
    val lastTactic: String? = null,
    val nextTactic: String? = null,
    val outcomeClassification: String? = null,
    val errorClassification: String? = null,
    val retryEligibility: Boolean = true,
    val retryAuthorizedFingerprint: String? = null,
    val rejectedQueries: List<RejectedResearchQuery> = emptyList(),
    val activeResearchStrategyJson: String? = null,
    val repairLineage: StructureRepairLineage? = null,
) {
    val effectiveProgressScore: Double
        get() = when {
            status == AgentTaskStatus.COMPLETED && progressScore <= 0.0 -> 1.0
            else -> progressScore.coerceIn(0.0, 1.0)
        }
}

data class AgentAttempt(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String?,
    val status: AgentAttemptStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val modelId: String,
    val councilRole: CouncilRole? = null,
    val role: AgentTaskRole? = null,
    val selectionReason: String? = null,
    val previousRoute: String? = null,
    val cooldownState: String? = null,
    val provider: String? = null,
    val finishReason: String? = null,
    val nativeFinishReason: String? = null,
    val resolvedModel: String? = null,
    val responseId: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val webSearchRequests: Int? = null,
    val webFetchRequests: Int? = null,
    val discoveredLeads: Int? = null,
    val rabbitHoleIterations: Int? = null,
    val error: String? = null,
)

/**
 * Detailed evaluation of an agent execution step quality.
 */
internal data class StepQualityEvaluation(
    val passed: Boolean,
    val boundedResearchRecoveryAccepted: Boolean = false,
    val completionScore: Double = 1.0,
    val criticalCheckFailed: Boolean = false,
    val researchQuality: ResearchQualityDecision? = null, // Refers to external model
    val correctionClaimGatePassed: Boolean = true,
    val preciseSourceGatePassed: Boolean = true,
    val toolUseGatePassed: Boolean = true,
    val impreciseSourceSelections: List<ImpreciseClaimSourceSelection> = emptyList(), // Refers to external model
    val reasons: List<String> = emptyList(),
)

/**
 * Hook for performing actions before a task result is committed to durable storage.
 */
internal interface BeforeTaskResultCommitHook {
    fun beforeCommit(
        goalId: String,
        taskId: String,
        ownership: ExecutionOwnership,
    )
}
