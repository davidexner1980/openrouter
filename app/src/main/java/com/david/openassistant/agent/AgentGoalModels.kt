package com.david.openassistant.agent

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class AgentGoal(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val submissionId: String? = null,
    val userRequest: String,
    val title: String,
    val objective: String,
    val finalOutputDescription: String,
    val confirmedConstraints: List<String> = emptyList(),
    val inferredPreferences: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val evidenceRequirements: List<String> = emptyList(),
    val preferredSourceTypes: List<String> = emptyList(),
    val freshnessRequirement: String? = null,
    val exclusions: List<String> = emptyList(),
    val sourceMessageIds: List<String> = emptyList(),
    val groundedConstraints: List<GroundedConstraint> = emptyList(),
    val status: AgentGoalStatus,
    val plannerModelId: String,
    val executionModelId: String,
    val routingStage: AgentRoutingStage = AgentRoutingStage.AUTO_BETA,
    val requestedModelProfileName: String? = null,
    val routingPolicyProvenance: RoutingPolicyProvenance = RoutingPolicyProvenance.EXPLICIT_USER_SELECTION,
    val freeOnly: Boolean = false,
    val tasks: List<AgentTask>,
    val acceptanceCriteria: List<AgentAcceptanceCriterion> = emptyList(),
    val acceptanceChecks: List<AgentAcceptanceCheck> = emptyList(),
    val attempts: List<AgentAttempt> = emptyList(),
    val evidence: List<AgentEvidence> = emptyList(),
    val sourceReads: List<SourceRead> = emptyList(),
    val evidenceCandidates: List<EvidenceCandidate> = emptyList(),
    val normalizedFacts: List<NormalizedFact> = emptyList(),
    val acceptedClaims: List<AcceptedClaim> = emptyList(),
    val claims: List<AgentClaim> = emptyList(),
    val evidenceLinks: List<AgentEvidenceLink> = emptyList(),
    val checkpoints: List<AgentCheckpoint> = emptyList(),
    val conceptCandidates: List<AgentConceptCandidate> = emptyList(),
    val refinements: List<String> = emptyList(),
    val events: List<AgentEvent> = emptyList(),
    val modelCooldowns: Map<String, Long> = emptyMap(),
    val executionLease: AgentExecutionLease? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val totalCostUsdMicros: Long = 0L,
    val totalTokens: Int = 0,
    val verificationRound: Int = 0,
    val verificationCorrectionStreak: Int = 0,
    val result: String? = null,
    val error: String? = null,
    val blockedReason: String? = null,
    val terminalResultDelivered: Boolean = false,
    val nextRetryAt: Long? = null,
    val networkWaitStartedAt: Long? = null,
    val networkRetryCount: Int = 0,
    val networkWaitReason: String? = null,
    val resumeStatusAfterNetwork: AgentGoalStatus? = null,
    val requestAttempts: List<ProviderRequestAttempt> = emptyList(),
    val idempotencyRecords: List<IdempotencyRecord> = emptyList(),
    val monitorOutbox: List<MonitorOutboxRecord> = emptyList(),
    val routeFingerprints: List<RouteFailureFingerprint> = emptyList(),
    val bodyBuilderClaims: List<BodyBuilderProposalClaim> = emptyList(),
    val quarantinedRecords: List<QuarantinedRecord> = emptyList(),
    val isCorrupt: Boolean = false,
    val resolvedResearchRequest: ResolvedResearchRequest? = null,
    val requiresUserClarification: Boolean = false,
    val clarificationDetails: String? = null,
    val blockedSources: List<BlockedSourceRecord> = emptyList(),
    val allocationProfileName: String? = null,
    val allocationSummary: String? = null,
    val lastAllocationReason: String? = null,
    val planRevision: Int = 0,
    val lastMeaningfulProgressAt: Long? = null,
    val noProgressCount: Int = 0,
    val blockerRecoveryCondition: String? = null,
    val finalValidationResult: String? = null,
    val attemptedStrategies: List<String> = emptyList(),
    val operationFingerprints: List<String> = emptyList(),
    val classifiedFailures: List<String> = emptyList(),
    val leaseGeneration: Int = 0,
    val lastResumeReason: ResumeReason? = null,
) {
    fun allocationSnapshot(policy: AutonomyPolicy = AutonomyPolicy.DEFAULT): ResearchAllocationSnapshot {
        val profile = AgentResearchAllocator.profileForGoal(this, policy)
        val gaps = AgentResearchAllocator.evaluateGaps(this, profile)
        val nextTaskSelection = AgentResearchAllocator.chooseNextTask(this, profile)
        
        return ResearchAllocationSnapshot(
            profile = profile,
            currentTaskId = nextTaskSelection.taskId,
            currentTaskReason = nextTaskSelection.reason,
            remainingSourceGap = gaps.remainingSourceGap,
            remainingDomainGap = gaps.remainingDomainGap,
            remainingPrimarySourceGap = gaps.remainingPrimarySourceGap,
            remainingContradictionGap = gaps.remainingContradictionGap,
            estimatedEffortLabel = gaps.estimatedEffortLabel,
            lastAllocationReason = lastAllocationReason
        )
    }

    val totalCostUsd: Double
        get() = totalCostUsdMicros.toDouble() / 1_000_000.0

    val completedTaskCount: Int
        get() = tasks.count { it.status == AgentTaskStatus.COMPLETED }

    val stepProgressFraction: Float
        get() = if (tasks.isEmpty()) 0f else completedTaskCount.toFloat() / tasks.size.toFloat()

    val denseProgressScore: Double
        get() {
            if (tasks.isEmpty()) return 0.0
            val totalWeight = tasks.sumOf { it.weight.coerceAtLeast(0.1) }
            if (totalWeight <= 0.0) return 0.0
            return tasks.sumOf { it.weight.coerceAtLeast(0.1) * it.effectiveProgressScore }
                .div(totalWeight)
                .coerceIn(0.0, 1.0)
        }

    val progressFraction: Float
        get() = denseProgressScore.toFloat()

    val isReadyForVerification: Boolean
        get() = tasks.isNotEmpty() && tasks.all { it.status == AgentTaskStatus.COMPLETED || it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }

    val acceptanceScore: Double
        get() {
            if (acceptanceCriteria.isEmpty()) {
                return if (status in setOf(
                        AgentGoalStatus.COMPLETED,
                        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
                        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
                    )
                ) 1.0 else denseProgressScore
            }
            val checksById = acceptanceChecks.associateBy { it.criterionId }
            val totalWeight = acceptanceCriteria.sumOf { it.weight.coerceAtLeast(0.1) }
            return acceptanceCriteria.sumOf { criterion ->
                criterion.weight.coerceAtLeast(0.1) * (checksById[criterion.id]?.score ?: 0.0).coerceIn(0.0, 1.0)
            }.div(totalWeight).coerceIn(0.0, 1.0)
        }

    val supportedClaimCount: Int
        get() = claims.count { it.support == AgentClaimSupport.SUPPORTED }

    val partialClaimCount: Int
        get() = claims.count { it.support == AgentClaimSupport.PARTIAL }

    val unsupportedClaimCount: Int
        get() = claims.count { it.support == AgentClaimSupport.UNSUPPORTED }

    val contradictedClaimCount: Int
        get() = claims.count { it.support == AgentClaimSupport.CONTRADICTED }

    val graphHealthScore: Double
        get() {
            if (claims.isEmpty()) return 1.0
            val supportedWeight = supportedClaimCount + (partialClaimCount * 0.5)
            return (supportedWeight / claims.size.toDouble()).coerceIn(0.0, 1.0)
        }

    val integrityScore: Double
        get() {
            val graphWeight = if (claims.isEmpty()) 0.0 else 0.20
            val denominator = 0.45 + 0.35 + graphWeight
            return ((denseProgressScore * 0.45) + (acceptanceScore * 0.35) + (graphHealthScore * graphWeight))
                .div(denominator)
                .coerceIn(0.0, 1.0)
        }

    val nextRunnableTask: AgentTask?
        get() = nextRunnableTask(skipCooldowns = false)

    fun nextRunnableTask(skipCooldowns: Boolean = false): AgentTask? {
        val now = System.currentTimeMillis()
        val completedIds = tasks
            .filter { it.status == AgentTaskStatus.COMPLETED }
            .mapTo(mutableSetOf()) { it.id }
        
        // Find tasks that are not blocked by dependencies
        val dependencySatisfiedTasks = tasks
            .filter { it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.CANCELLED && it.status != AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }
            .filter { it.branchExhaustionReason == null }
            .filter { it.dependsOn.all(completedIds::contains) }
            .sortedBy { it.order }

        // Among those, find ones that are not in cooldown or network wait
        return dependencySatisfiedTasks.firstOrNull { task ->
            val readyTime = (!skipCooldowns && task.cooldownUntil != null && now < task.cooldownUntil)
            val networkWait = (task.failureClass == "network_resolution" && task.waitCondition != null)
            !readyTime && !networkWait
        }
    }

    val totalWebSearches: Int
        get() = attempts.groupBy { it.taskId }.values.sumOf { taskAttempts ->
            taskAttempts.maxOfOrNull { it.webSearchRequests ?: 0 } ?: 0
        }

    val totalWebFetches: Int
        get() = attempts.groupBy { it.taskId }.values.sumOf { taskAttempts ->
            taskAttempts.maxOfOrNull { it.webFetchRequests ?: 0 } ?: 0
        }

    val totalDiscoveredLeads: Int
        get() = attempts.groupBy { it.taskId }.values.sumOf { taskAttempts ->
            taskAttempts.maxOfOrNull { it.discoveredLeads ?: 0 } ?: 0
        }

    val totalRabbitHoleIterations: Int
        get() = attempts.groupBy { it.taskId }.values.sumOf { taskAttempts ->
            taskAttempts.maxOfOrNull { it.rabbitHoleIterations ?: 0 } ?: 0
        }

    fun withAdditionalUsage(
        tokens: Int?,
        costUsd: Double?,
    ): AgentGoal {
        val tokenDelta = tokens?.coerceAtLeast(0) ?: 0
        val costDeltaMicros = when {
            costUsd == null || !costUsd.isFinite() || costUsd <= 0.0 -> 0L
            else -> try {
                BigDecimal.valueOf(costUsd)
                    .movePointRight(6)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            } catch (e: Exception) {
                (costUsd * 1_000_000.0).toLong()
            }
        }

        return this.copy(
            totalTokens = try { Math.addExact(totalTokens, tokenDelta) } catch (e: ArithmeticException) { Int.MAX_VALUE },
            totalCostUsdMicros = try { Math.addExact(totalCostUsdMicros, costDeltaMicros) } catch (e: ArithmeticException) { Long.MAX_VALUE },
            updatedAt = System.currentTimeMillis()
        )
    }
}

data class MissionQuarantineEntry(
    val fileName: String,
    val reason: String,
    val recoveryArtifactPath: String?,
    val baseFileSize: Long,
    val backupPresent: Boolean,
    val detectedAt: Long = System.currentTimeMillis(),
)

data class AgentSnapshot(
    val goals: List<AgentGoal> = emptyList(),
    val selectedGoalId: String? = null,
    val quarantinedMissions: List<MissionQuarantineEntry> = emptyList(),
) {
    val selectedGoal: AgentGoal?
        get() = selectedGoalId?.let { selectedId -> goals.firstOrNull { it.id == selectedId } }
            ?: goals.maxByOrNull { it.updatedAt }
}
