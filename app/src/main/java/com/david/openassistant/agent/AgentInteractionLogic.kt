package com.david.openassistant.agent

fun AgentGoalStatus.isFinalTerminalStatus(): Boolean = this in setOf(
    AgentGoalStatus.COMPLETED,
    AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
    AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
    AgentGoalStatus.CANCELLED,
    AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
    AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
    AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
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
)

data class AgentToolExecution(
    val toolName: String,
    val summary: String,
    val succeeded: Boolean,
)

data class AgentStepResult(
    val content: String,
    val summary: AgentApiSummary,
    val sources: List<AgentSourceCitation> = emptyList(),
    val completionScore: Double = 1.0,
    val acceptanceChecks: List<AgentAcceptanceCheck> = emptyList(),
    val claims: List<AgentClaim> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val toolExecutions: List<AgentToolExecution> = emptyList(),
    val structuredOutputRepaired: Boolean = false,
    val queryFingerprints: List<String> = emptyList(),
    val rejectedQueries: List<RejectedResearchQuery> = emptyList(),
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
    fun getAvailableActions(goal: AgentGoal): Set<MissionUiAction> {
        val now = System.currentTimeMillis()
        val hasActiveLease = goal.executionLease?.let { !AgentLeasePolicy.isStale(it, now) } ?: false
        
        // A worker is truly active only if a fresh lease exists and status suggests active processing
        val isWorkerTrulyActive = hasActiveLease && (
            goal.status == AgentGoalStatus.PLANNING ||
                goal.status == AgentGoalStatus.QUEUED ||
                goal.status == AgentGoalStatus.RUNNING ||
                goal.status == AgentGoalStatus.VERIFYING
            )
        
        val isTerminal = goal.status in setOf(
            AgentGoalStatus.COMPLETED,
            AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
            AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
            AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
            AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
            AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
        )

        // A mission is stranded if its status is active but no worker is heartbeating
        val isStranded = !hasActiveLease && (
            goal.status == AgentGoalStatus.PLANNING ||
                goal.status == AgentGoalStatus.QUEUED ||
                goal.status == AgentGoalStatus.RUNNING ||
                goal.status == AgentGoalStatus.VERIFYING
            )

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
