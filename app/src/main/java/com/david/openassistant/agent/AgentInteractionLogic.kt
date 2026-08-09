package com.david.openassistant.agent

fun AgentGoalStatus.isFinalTerminalStatus(): Boolean = this in setOf(
    AgentGoalStatus.COMPLETED,
    AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
    AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
    AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
    AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
    AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
    AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
    AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
)

data class AgentApiSummary(
    val responseId: String? = null,
    val resolvedModel: String? = null,
    val role: AgentTaskRole? = null,
    val selectionReason: String? = null,
    val previousRoute: String? = null,
    val cooldownState: String? = null,
    val provider: String? = null,
    val finishReason: String? = null,
    val nativeFinishReason: String? = null,
    val httpStatusCode: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val webSearchRequests: Int? = null,
    val webFetchRequests: Int? = null,
    val discoveredLeads: Int? = null,
    val rabbitHoleIterations: Int? = null,
    val durationMs: Long? = null,
)

data class AgentToolExecution(
    val toolName: String,
    val summary: String,
    val succeeded: Boolean,
)

data class StructureRepairLineage(
    val originalResponseHash: String,
    val originalRequestFingerprint: String,
    val repairRequestFingerprint: String,
    val repairAttemptCount: Int,
    val repairReason: StructureRepairReason,
    val repairOutcome: StructureRepairOutcome,
    val preRepairContentChars: Int,
    val postRepairContentChars: Int,
    val preRepairRawClaims: Int,
    val postRepairRawClaims: Int,
    val preRepairRetainedClaims: Int,
    val postRepairRetainedClaims: Int,
    val preRepairSupportedClaims: Int,
    val postRepairSupportedClaims: Int,
)

data class AgentStepResult(
    val content: String,
    val summary: AgentApiSummary,
    val sources: List<AgentSourceCitation> = emptyList(),
    val sourceReads: List<SourceRead> = emptyList(),
    val completionScore: Double = 1.0,
    val acceptanceChecks: List<AgentAcceptanceCheck> = emptyList(),
    val claims: List<AgentClaim> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val toolExecutions: List<AgentToolExecution> = emptyList(),
    val structuredOutputRepaired: Boolean = false,
    val queryFingerprints: List<String> = emptyList(),
    val rejectedQueries: List<RejectedResearchQuery> = emptyList(),
    val repairLineage: StructureRepairLineage? = null,
)

data class AgentClaimReview(
    val claimId: String,
    val support: AgentClaimSupport,
    val explanation: String,
)

data class AgentVerificationResult(
    val passed: Boolean,
    val qualityScore: Double,
    val summary: String,
    val missingRequirements: List<String>,
    val acceptanceChecks: List<AgentAcceptanceCheck>,
    val claimReviews: List<AgentClaimReview>,
    val correctionInstructions: String?,
    val finalAnswer: String,
    val conceptCandidates: List<AgentConceptCandidate>,
    val apiSummary: AgentApiSummary,
    val structuredOutputRepaired: Boolean = false,
) {
    constructor(
        passed: Boolean,
        qualityScore: Double,
        summary: String,
        missingRequirements: List<String>,
        acceptanceChecks: List<AgentAcceptanceCheck>,
        claimReviews: List<AgentClaimReview>,
        correctionInstructions: String?,
        finalAnswer: String,
        conceptCandidates: List<AgentConceptCandidate>,
        apiSummary: AgentApiSummary,
    ) : this(
        passed = passed,
        qualityScore = qualityScore,
        summary = summary,
        missingRequirements = missingRequirements,
        acceptanceChecks = acceptanceChecks,
        claimReviews = claimReviews,
        correctionInstructions = correctionInstructions,
        finalAnswer = finalAnswer,
        conceptCandidates = conceptCandidates,
        apiSummary = apiSummary,
        structuredOutputRepaired = false,
    )
}

data class AgentIntegrityDecision(
    val passed: Boolean,
    val reasons: List<String>,
)

object MissionUiLogic {
    fun getAvailableActions(
        goal: AgentGoal, 
        isWorkManagerRunning: Boolean = false,
        hasUnsettledExchange: Boolean = false
    ): Set<MissionUiAction> {
        val now = System.currentTimeMillis()
        val hasActiveLease = goal.executionLease?.let { !AgentLeasePolicy.isStale(it, now) } ?: false
        
        // A worker is truly active only if a fresh lease exists OR WorkManager reports it as running,
        // OR an exchange is active (unsettled), and status suggests active processing
        val isWorkerTrulyActive = (hasActiveLease || isWorkManagerRunning || hasUnsettledExchange) &&
            goal.status.isActivePhase()
        
        val isTerminal = goal.status.isFinalTerminalStatus()

        // A mission is stranded if its status is active but no worker is heartbeating
        val isStranded = !hasActiveLease && goal.status.isActivePhase()

        return buildSet {
            // Only show PAUSE if a worker is actually heartbeating
            if (isWorkerTrulyActive && !isTerminal) add(MissionUiAction.PAUSE)
            
            // Show RESUME if mission is not terminal and either paused, failed, or stranded
            if (!isWorkerTrulyActive && !isTerminal && (
                    goal.status in setOf(
                        AgentGoalStatus.PAUSED,
                        AgentGoalStatus.FAILED,
                        AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                        AgentGoalStatus.BLOCKED,
                        AgentGoalStatus.WAITING_FOR_NETWORK,
                        AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
                        AgentGoalStatus.CANCELLED,
                    ) || isStranded
                )
            ) {
                add(MissionUiAction.RESUME)
            }
            
            // Only show STOP if NOT terminal and NOT already cancelled/cancelling
            if (!isTerminal && goal.status != AgentGoalStatus.CANCELLED && goal.status != AgentGoalStatus.CANCELLING) {
                add(MissionUiAction.STOP)
            }
            
            // Allow DELETE for terminal or failed missions
            if (isTerminal || goal.status == AgentGoalStatus.FAILED) {
                add(MissionUiAction.DELETE)
            }
        }
    }
}
