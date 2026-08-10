package com.david.openassistant.agent

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
                    ) || isStranded
                )
            ) {
                add(MissionUiAction.RESUME)
            }
            
            // Show RESTART only for CANCELLED
            if (goal.status == AgentGoalStatus.CANCELLED) {
                add(MissionUiAction.RESTART)
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

object AgentResultDeliveryLogic {
    fun deliverTerminalResultIfPending(
        goalId: String,
        agentStore: AgentStore,
        conversationStore: com.david.openassistant.data.local.ConversationStore,
        diagnostics: com.david.openassistant.data.diagnostics.RuntimeDiagnostics? = null
    ) {
        val goal = agentStore.loadSnapshot().goals.firstOrNull { it.id == goalId } ?: return
        val currentExecGen = goal.executionGeneration
        val deliveryKind = "TERMINAL_RESULT"
        
        // V43: Exactly-once delivery based on stable mission execution epoch
        val alreadyMarked = goal.deliveryRecords.any { 
            !it.isLegacy && it.executionGeneration == currentExecGen && it.deliveryKind == deliveryKind 
        }

        if (goal.status.isFinalTerminalStatus() && !alreadyMarked) {
            val resultText = goal.result ?: "The mission ended without producing a final result summary."
            val content = "### Research Mission Final Status: ${goal.status.name}\n\n**${goal.title}**\n\n$resultText\n\n[Open Report](mission://${goal.id}?gen=$currentExecGen)"
            
            // V43: Deterministic message identity (goalId + executionGeneration + kind)
            val deterministicId = "mission-delivery-${goal.id}-${currentExecGen}-${deliveryKind}"
            
            val message = com.david.openassistant.data.openrouter.ChatMessage(
                id = deterministicId,
                role = com.david.openassistant.data.openrouter.ChatRole.ASSISTANT,
                content = content
            )
            
            // Phase 1: Write to conversation (Commit Side Effect)
            try {
                val snapshot = conversationStore.loadSnapshot()
                val targetConv = snapshot.conversations.firstOrNull { it.id == goal.conversationId } ?: run {
                    diagnostics?.warning("mission_result_delivery_target_conversation_missing", mapOf("goal_id" to goalId, "conv_id" to goal.conversationId))
                    return 
                }
                
                // Idempotency: skip if already present
                if (targetConv.messages.none { it.id == deterministicId }) {
                    val updatedConversations = snapshot.conversations.map { conversation ->
                        if (conversation.id == goal.conversationId) {
                            conversation.copy(
                                messages = conversation.messages + message,
                                updatedAt = System.currentTimeMillis()
                            )
                        } else conversation
                    }
                    conversationStore.saveSnapshot(snapshot.copy(conversations = updatedConversations))
                    
                    // Verify durability before committing the marker
                    val reloaded = conversationStore.loadSnapshot().conversations.firstOrNull { it.id == goal.conversationId }
                    if (reloaded == null || reloaded.messages.none { it.id == deterministicId }) {
                         diagnostics?.error("mission_result_delivery_persistence_verification_failed", IllegalStateException("Message not found after save"), mapOf("goal_id" to goalId))
                         return
                    }
                }
            } catch (e: Exception) {
                diagnostics?.error("mission_result_delivery_store_failed", e, mapOf("goal_id" to goalId))
                return
            }
            
            // Phase 2: Commit marker to agent store (Commit Durable Intent)
            agentStore.updateGoal(goal.id) { current ->
                // Ensure we don't duplicate markers if already present from a race
                if (current.deliveryRecords.any { !it.isLegacy && it.executionGeneration == currentExecGen && it.deliveryKind == deliveryKind }) {
                    current
                } else {
                    current.copy(
                        deliveryRecords = current.deliveryRecords + DeliveryRecord(
                            generation = current.leaseGeneration, // Provenance
                            deliveryKind = deliveryKind,
                            executionGeneration = currentExecGen, // Stable epoch
                            isLegacy = false
                        ),
                        terminalResultDelivered = true
                    )
                }
            }
            
            diagnostics?.info("mission_result_delivered", mapOf("goal_id" to goalId, "conversation_id" to goal.conversationId, "ex_gen" to currentExecGen, "status" to goal.status.name))
        }
    }
}
