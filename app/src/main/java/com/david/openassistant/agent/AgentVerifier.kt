package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.AgentModelSelector
import com.david.openassistant.agent.AgentRoutingPolicy
import com.david.openassistant.agent.IdempotencyEffectType
import com.david.openassistant.agent.IdempotencyRecord
import com.david.openassistant.agent.IdempotencyState
import com.david.openassistant.agent.UsageSource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentVerifier(
    private val client: AgentOpenRouterClient,
    private val store: AgentStore,
    private val diagnostics: RuntimeDiagnostics,
    private val autonomyPolicy: AutonomyPolicy
) {
    suspend fun verifyAndFinish(
        apiKey: String,
        goal: AgentGoal,
        models: List<OpenRouterModel> = emptyList(),
    ): WorkerOutcome {
        var preVerificationExcludedClaimCount = 0
        val verificationSnapshot = store.updateGoal(goal.id) { current ->
            if (current.status !in setOf(AgentGoalStatus.RUNNING, AgentGoalStatus.QUEUED)) {
                current
            } else {
                val normalizedClaims = normalizeDurableClaims(current.tasks, current.claims)
                val publicationGraph = compactPublicationGraph(
                    claims = normalizedClaims,
                    evidenceLinks = current.evidenceLinks,
                )
                preVerificationExcludedClaimCount = publicationGraph.excludedClaimCount
                val startedEvents = appendEvent(
                    current.events,
                    "Independent verification and publication gate started.",
                )
                current.copy(
                    status = AgentGoalStatus.VERIFYING,
                    claims = publicationGraph.claims,
                    evidenceLinks = retainEvidenceLinks(publicationGraph.evidenceLinks),
                    events = if (publicationGraph.excludedClaimCount > 0) {
                        appendEvent(
                            startedEvents,
                            "Moved ${publicationGraph.excludedClaimCount} non-publishable claim(s) out of the active graph; their evidence remains preserved.",
                        )
                    } else {
                        startedEvents
                    },
                )
            }
        }
        val verificationGoal = verificationSnapshot.goals.firstOrNull { it.id == goal.id }
        if (verificationGoal?.status != AgentGoalStatus.VERIFYING) return WorkerOutcome.DONE
        val verificationStartedAt = System.currentTimeMillis()
        diagnostics.info(
            "agent_verification_started",
            mapOf(
                "goal_id" to goal.id,
                "verification_round" to verificationGoal.verificationRound,
                "claim_count" to verificationGoal.claims.size,
                "evidence_count" to verificationGoal.evidence.size,
            ),
        )

        return try {
            val latest = findGoal(goal.id) ?: return WorkerOutcome.FAIL
            val lease = latest.executionLease ?: return WorkerOutcome.FAIL
            val parentOperationId = "op-verify-${UUID.randomUUID()}"
            val missionContext = ProviderRequestContext.Mission(
                goalId = goal.id,
                workerId = lease.workerId,
                taskId = null,
                attemptId = lease.attemptId,
                executionGeneration = lease.generation,
                role = AgentTaskRole.PRIMARY_REASONING,
                operation = MissionOperation.VERIFY_GOAL,
                parentOperationId = parentOperationId,
            )
            val verification = client.verifyGoal(apiKey, AgentRoutingPolicy.guardModel(latest, latest.plannerModelId), latest, requestContext = missionContext)
            currentCoroutineContext().ensureActive()
            val currentAfterVerification = findGoal(goal.id) ?: return WorkerOutcome.FAIL
            if (currentAfterVerification.status in setOf(AgentGoalStatus.PAUSED, AgentGoalStatus.CANCELLED)) {
                return WorkerOutcome.DONE
            }
            val reviewedClaims = AgentIntegrityEvaluator.applyClaimReviews(
                currentAfterVerification.claims,
                verification.claimReviews,
            )
            val reviewedLinks = AgentIntegrityEvaluator.reconcileEvidenceLinks(
                reviewedClaims,
                currentAfterVerification.evidenceLinks,
            )
            val publicationGraph = compactPublicationGraph(
                claims = reviewedClaims,
                evidenceLinks = reviewedLinks,
            )
            val mergedChecks = AgentIntegrityEvaluator.mergeChecks(
                currentAfterVerification.acceptanceCriteria,
                verification.acceptanceChecks,
            )
            val evaluatedGoal = currentAfterVerification.copy(
                claims = publicationGraph.claims,
                evidenceLinks = retainEvidenceLinks(publicationGraph.evidenceLinks),
                acceptanceChecks = mergedChecks,
            )
            val allocationProfile = AgentResearchAllocator.profileForGoal(evaluatedGoal, autonomyPolicy)
            val researchPublicationGate = ResearchQualityGate.evaluateGoal(evaluatedGoal, autonomyPolicy, allocationProfile)
            val recoveryRoutes = selectVerificationRecoveryRoutes(
                researchGateReasons = researchPublicationGate.reasons,
                verificationMissingRequirements = verification.missingRequirements,
            )
            val recoveryCapabilities = recoveryRoutes.map { it.capability.name }.distinct().joinToString(",")
            val integrity = AgentIntegrityEvaluator.evaluate(evaluatedGoal, verification)
            val findingCodes = verificationFindingCodes(integrity.reasons)
            val excludedClaimCount = preVerificationExcludedClaimCount + publicationGraph.excludedClaimCount
            val convergenceSnapshot = VerificationConvergenceSnapshot(
                qualityScore = verification.qualityScore,
                findingCodes = findingCodes,
                excludedClaimCount = excludedClaimCount,
            )
            val previousConvergenceSnapshot = latestVerificationConvergenceSnapshot(
                currentAfterVerification.evidence,
            )
            val convergenceStalled = currentAfterVerification.verificationCorrectionStreak > 0 &&
                hasVerificationConvergenceStalled(previousConvergenceSnapshot, convergenceSnapshot)
            diagnostics.info(
                "agent_verification_evaluated",
                mapOf(
                    "goal_id" to goal.id,
                    "duration_ms" to (System.currentTimeMillis() - verificationStartedAt),
                    "quality_score" to verification.qualityScore,
                    "publication_gate_passed" to integrity.passed,
                    "gate_finding_count" to integrity.reasons.size,
                    "gate_finding_codes" to findingCodes.joinToString(","),
                    "research_gate_finding_codes" to researchQualityFindingCodes(researchPublicationGate.reasons)
                        .joinToString(","),
                    "missing_requirement_count" to verification.missingRequirements.size,
                    "excluded_claim_count" to excludedClaimCount,
                    "correction_streak" to currentAfterVerification.verificationCorrectionStreak,
                    "convergence_stalled" to convergenceStalled,
                    "recovery_capability" to recoveryCapabilities,
                    "recovery_task_count" to recoveryRoutes.size,
                    "recovery_routes" to recoveryRoutes.joinToString(",") { it.taskIdPrefix },
                    "structured_output_repaired" to verification.structuredOutputRepaired,
                    "verification_response_status" to verificationResponseStatus(verification),
                ),
            )
            val verificationEvidence = AgentEvidence(
                kind = AgentEvidenceKind.VERIFICATION,
                title = "Independent verification",
                summary = buildString {
                    append(verification.summary)
                    if (!integrity.passed) append(" Deterministic publication gate rejected the result.")
                }.take(500),
                content = verificationEvidenceContent(
                    content = buildString {
                        appendLine(verification.summary)
                        appendLine()
                        appendLine("Quality score: ${verification.qualityScore.asPercent()}")
                        if (verification.structuredOutputRepaired) {
                            appendLine("The provider's verifier response was preserved and repaired into the required object shape before deterministic evaluation.")
                        }
                        if (verification.missingRequirements.isNotEmpty()) {
                            appendLine()
                            appendLine("Missing requirements:")
                            verification.missingRequirements.forEach { appendLine("- $it") }
                        }
                        if (integrity.reasons.isNotEmpty()) {
                            appendLine()
                            appendLine("Deterministic gate findings:")
                            integrity.reasons.forEach { appendLine("- $it") }
                        }
                        if (excludedClaimCount > 0) {
                            appendLine()
                            appendLine(
                                "$excludedClaimCount contradicted, unsupported, or imprecisely sourced claim(s) were excluded from the active publication graph during this verification round. Their underlying evidence remains preserved.",
                            )
                        }
                    },
                    snapshot = convergenceSnapshot,
                    maximumCharacters = MAX_EVIDENCE_CONTENT_CHARS,
                ),
            )
            val accountingKey = "verification_accounting_${lease.attemptId}"
            val baseUpdated = if (currentAfterVerification.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) {
                currentAfterVerification
            } else {
                val updated = currentAfterVerification.withAdditionalUsage(verification.apiSummary.totalTokens, verification.apiSummary.costUsd)
                val record = IdempotencyRecord(
                    key = accountingKey,
                    effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
                    state = IdempotencyState.COMMITTED,
                    claimOwner = lease.workerId,
                    committedAt = System.currentTimeMillis(),
                    completedBy = lease.workerId
                )
                updated.copy(idempotencyRecords = updated.idempotencyRecords + record)
            }

            if (integrity.passed) {
                val rawPublishedResult = verification.finalAnswer.ifBlank {
                    baseUpdated.evidence
                        .lastOrNull { it.kind == AgentEvidenceKind.MODEL_OUTPUT }
                        ?.content
                        .orEmpty()
                }
                val publishedResult = publicationAnswerWithSourceLinks(
                    answer = rawPublishedResult,
                    claims = publicationGraph.claims,
                )
                val completedSnapshot = store.updateGoal(goal.id) { current ->
                    if (current.status != AgentGoalStatus.VERIFYING) return@updateGoal current
                    val hasUnresolved = mergedChecks.any { it.status == AgentAcceptanceCheckStatus.PARTIAL }
                    val finalStatus = if (hasUnresolved) AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS else AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE
                    val completed = current.copy(
                        status = finalStatus,
                        result = publishedResult,
                        acceptanceChecks = mergedChecks,
                        claims = publicationGraph.claims,
                        evidenceLinks = retainEvidenceLinks(publicationGraph.evidenceLinks),
                        conceptCandidates = mergeConceptCandidates(current.conceptCandidates, verification.conceptCandidates),
                        evidence = appendEvidence(current.evidence, verificationEvidence),
                        events = appendEvent(current.events, "Verification and deterministic publication gate passed ($finalStatus)."),
                        totalTokens = baseUpdated.totalTokens,
                        totalCostUsdMicros = baseUpdated.totalCostUsdMicros,
                        idempotencyRecords = baseUpdated.idempotencyRecords,
                        error = null,
                    )
                    completed.copy(
                        checkpoints = appendCheckpoint(
                            completed.checkpoints,
                            AgentCheckpoint(
                                sequence = completed.checkpoints.size + 1,
                                completedTaskIds = completed.tasks.map { it.id },
                                progressScore = 1.0,
                                note = "Verified final result published. Learning candidates remain inactive proposals.",
                            ),
                        ),
                    )
                }
                val completedGoal = completedSnapshot.goals.firstOrNull { it.id == goal.id }
                diagnostics.info(
                    "agent_verification_passed",
                    mapOf(
                        "goal_id" to goal.id,
                        "duration_ms" to (System.currentTimeMillis() - verificationStartedAt),
                        "total_tokens" to baseUpdated.totalTokens,
                        "verification_response_status" to verificationResponseStatus(verification),
                    ),
                )
                when (completedGoal?.status) {
                    AgentGoalStatus.COMPLETED,
                    AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
                    AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
                    AgentGoalStatus.PAUSED,
                    AgentGoalStatus.CANCELLED,
                    AgentGoalStatus.FINALIZING,
                    null,
                    -> WorkerOutcome.DONE

                    else -> WorkerOutcome.FAIL
                }
            } else if (
                convergenceStalled ||
                !canQueueVerificationCorrection(currentAfterVerification.verificationCorrectionStreak)
            ) {
                val isPartialEvidence = convergenceStalled && currentAfterVerification.claims.any { it.type == AgentClaimType.FACT }
                val terminalStatus = if (isPartialEvidence) AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE else AgentGoalStatus.FAILED
                
                val failureMessage = if (convergenceStalled) {
                    if (isPartialEvidence) {
                        "Verification detected that the latest bounded recovery pass did not materially improve results. Ending as BLOCKED_WITH_PARTIAL_EVIDENCE. Preserved facts remain available."
                    } else {
                        "Verification detected that the latest bounded recovery pass did not materially improve the repeated publication findings. No unverified answer was published. A fresh correction cycle will retry automatically from the strongest preserved checkpoint after backoff."
                    }
                } else {
                    buildString {
                        append("Verification completed ")
                        append(MAX_VERIFICATION_CORRECTION_PASSES)
                        append(" bounded correction passes, but the publication gate still found ")
                        append(integrity.reasons.size)
                        append(" issue(s). No unverified answer was published. A fresh correction cycle will retry automatically from preserved evidence after backoff.")
                    }
                }
                val failedSnapshot = store.updateGoal(goal.id) { current ->
                    if (current.status != AgentGoalStatus.VERIFYING) return@updateGoal current
                    current.copy(
                        status = terminalStatus,
                        acceptanceChecks = mergedChecks,
                        claims = publicationGraph.claims,
                        evidenceLinks = retainEvidenceLinks(publicationGraph.evidenceLinks),
                        conceptCandidates = mergeConceptCandidates(current.conceptCandidates, verification.conceptCandidates),
                        evidence = appendEvidence(current.evidence, verificationEvidence),
                        events = appendEvent(current.events, failureMessage),
                        totalTokens = baseUpdated.totalTokens,
                        totalCostUsdMicros = baseUpdated.totalCostUsdMicros,
                        idempotencyRecords = baseUpdated.idempotencyRecords,
                        error = failureMessage,
                    )
                }
                val failedGoal = failedSnapshot.goals.firstOrNull { it.id == goal.id }
                diagnostics.warning(
                    if (convergenceStalled) {
                        "agent_verification_convergence_stalled"
                    } else {
                        "agent_verification_convergence_exhausted"
                    },
                    mapOf(
                        "goal_id" to goal.id,
                        "correction_passes" to currentAfterVerification.verificationCorrectionStreak,
                        "finding_count" to integrity.reasons.size,
                        "finding_codes" to findingCodes.joinToString(","),
                        "research_gate_finding_codes" to researchQualityFindingCodes(researchPublicationGate.reasons)
                            .joinToString(","),
                        "excluded_claim_count" to excludedClaimCount,
                        "previous_quality_score" to previousConvergenceSnapshot?.qualityScore,
                        "quality_score" to convergenceSnapshot.qualityScore,
                        "previous_excluded_claim_count" to previousConvergenceSnapshot?.excludedClaimCount,
                        "recovery_capability" to recoveryCapabilities,
                        "recovery_task_count" to recoveryRoutes.size,
                        "recovery_routes" to recoveryRoutes.joinToString(",") { it.taskIdPrefix },
                        "structured_output_repaired" to verification.structuredOutputRepaired,
                        "verification_response_status" to verificationResponseStatus(verification),
                    ),
                )
                val newestCorrectionId = failedGoal?.tasks
                    ?.asReversed()
                    ?.firstOrNull { it.capability == AgentCapability.CORRECT }
                    ?.id
                if (failedGoal?.status == AgentGoalStatus.FAILED && newestCorrectionId != null) {
                    val restartedSnapshot = store.updateGoal(goal.id) { current ->
                        if (current.status != AgentGoalStatus.FAILED) return@updateGoal current
                        current.copy(
                            status = AgentGoalStatus.QUEUED,
                            tasks = current.tasks.map { existing ->
                                if (existing.id == newestCorrectionId) {
                                    existing.copy(
                                        status = AgentTaskStatus.FAILED,
                                        attemptCount = 0,
                                        lastError = failureMessage,
                                        finishedAt = System.currentTimeMillis(),
                                    )
                                } else {
                                    existing
                                }
                            },
                            verificationCorrectionStreak = 0,
                            events = appendEvent(
                                current.events,
                                "Automatic verification recovery reopened the newest evidence-only correction with a fresh local attempt window and preserved every source, claim, and checkpoint.",
                            ),
                            error = null,
                        )
                    }
                    val restartedGoal = restartedSnapshot.goals.firstOrNull { it.id == goal.id }
                    if (restartedGoal?.status == AgentGoalStatus.QUEUED) {
                        diagnostics.warning(
                            "agent_verification_cycle_restarted_automatically",
                            mapOf(
                                "goal_id" to goal.id,
                                "correction_task_id" to newestCorrectionId,
                                "prior_correction_passes" to currentAfterVerification.verificationCorrectionStreak,
                                "convergence_stalled" to convergenceStalled,
                                "finding_count" to integrity.reasons.size,
                            ),
                        )
                        WorkerOutcome.RETRY
                    } else {
                        WorkerOutcome.DONE
                    }
                } else {
                    when (failedGoal?.status) {
                        AgentGoalStatus.PAUSED,
                        AgentGoalStatus.CANCELLED,
                        AgentGoalStatus.FAILED,
                        AgentGoalStatus.FINALIZING,
                        null,
                        -> WorkerOutcome.DONE

                        else -> WorkerOutcome.FAIL
                    }
                }
            } else {
                val correctionFindings = actionableVerificationFindings(
                    missingRequirements = verification.missingRequirements,
                    integrityReasons = integrity.reasons,
                )
                val existingTaskIds = currentAfterVerification.tasks.mapTo(mutableSetOf()) { it.id }
                val precedingDependencies = currentAfterVerification.tasks
                    .filter { it.status == AgentTaskStatus.COMPLETED }
                    .map { it.id }
                    .toMutableList()
                val correctionTasks = recoveryRoutes.mapIndexed { routeIndex, recoveryRoute ->
                    val taskFindings = if (recoveryRoute.evidenceOnly) {
                        correctionFindings
                    } else {
                        recoveryRoute.targetFindings.ifEmpty {
                            researchPublicationGate.reasons.take(1)
                        }
                    }
                    val correctionId = uniqueTaskId(
                        preferred = "${recoveryRoute.taskIdPrefix}_${currentAfterVerification.verificationRound + 1}",
                        existingIds = existingTaskIds,
                    )
                    existingTaskIds += correctionId
                    val correctionTask = AgentTask(
                        id = correctionId,
                        order = currentAfterVerification.tasks.size + routeIndex,
                        title = recoveryRoute.title,
                        instructions = if (!recoveryRoute.evidenceOnly) {
                            buildString {
                                appendLine("Close this deterministic research-quality gap without repeating completed research roles:")
                                taskFindings.forEach { appendLine("- $it") }
                                appendLine("Preserve exact HTTPS URLs, analyze the source material, and produce precisely attributed factual claims.")
                                appendLine("A requested record may genuinely not exist. After at least three distinct query angles and substantive source reading, a precisely documented negative finding resolves the gap when it records the alternate paths attempted, contrary evidence, and how the answer must change. Never keep searching merely to force a predetermined conclusion.")
                                appendLine("This role is part of one bounded recovery bundle; later roles will run from its durable checkpoint.")
                            }
                        } else {
                            buildString {
                                appendLine("Correct every missing requirement and deterministic publication-gate finding from preserved evidence only.")
                                verification.correctionInstructions
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { appendLine(it) }
                                correctionFindings.forEach { appendLine("- $it") }
                                appendLine("Produce one replacement final result with explicit evidence support and no new research.")
                            }
                        },
                        capability = recoveryRoute.capability,
                        dependsOn = precedingDependencies.toList(),
                        status = AgentTaskStatus.QUEUED,
                        weight = 2.0,
                        acceptanceCriteria = taskFindings.mapIndexed { index, finding ->
                            AgentAcceptanceCriterion(
                                id = "${correctionId}_finding_${index + 1}",
                                description = "Resolve or explicitly bound publication finding after documented alternate-angle work: $finding",
                                weight = 1.0,
                            )
                        }.ifEmpty {
                            listOf(
                                AgentAcceptanceCriterion(
                                    id = "${correctionId}_verified",
                                    description = "The corrected result passes the independent verifier and deterministic publication gate.",
                                    weight = 1.0,
                                ),
                            )
                        },
                    )
                    precedingDependencies += correctionId
                    correctionTask
                }
                val correctionSnapshot = store.updateGoal(goal.id) { current ->
                    if (current.status != AgentGoalStatus.VERIFYING) return@updateGoal current
                    current.copy(
                        status = AgentGoalStatus.QUEUED,
                        tasks = current.tasks + correctionTasks,
                        verificationRound = current.verificationRound + 1,
                        verificationCorrectionStreak = current.verificationCorrectionStreak + 1,
                        acceptanceChecks = mergedChecks,
                        claims = publicationGraph.claims,
                        evidenceLinks = retainEvidenceLinks(publicationGraph.evidenceLinks),
                        conceptCandidates = mergeConceptCandidates(current.conceptCandidates, verification.conceptCandidates),
                        evidence = appendEvidence(current.evidence, verificationEvidence),
                        events = appendEvent(
                            current.events,
                            if (recoveryRoutes.all { it.evidenceOnly }) {
                                "Publication gate failed. Bounded evidence correction ${current.verificationCorrectionStreak + 1} of $MAX_VERIFICATION_CORRECTION_PASSES was queued from the preserved evidence."
                            } else {
                                "Publication gate found deterministic research deficits. Bounded recovery bundle ${current.verificationCorrectionStreak + 1} of $MAX_VERIFICATION_CORRECTION_PASSES queued ${correctionTasks.size} focused role(s)."
                            },
                        ),
                        totalTokens = baseUpdated.totalTokens,
                        totalCostUsdMicros = baseUpdated.totalCostUsdMicros,
                        idempotencyRecords = baseUpdated.idempotencyRecords,
                        error = (verification.summary + " " + integrity.reasons.joinToString(" ")).take(2_000),
                    )
                }
                val correctionGoal = correctionSnapshot.goals.firstOrNull { it.id == goal.id }
                diagnostics.warning(
                    "agent_verification_correction_queued",
                    mapOf(
                        "goal_id" to goal.id,
                        "correction_task_id" to correctionTasks.first().id,
                        "correction_task_ids" to correctionTasks.joinToString(",") { it.id },
                        "correction_task_count" to correctionTasks.size,
                        "verification_round" to (currentAfterVerification.verificationRound + 1),
                        "correction_pass" to (currentAfterVerification.verificationCorrectionStreak + 1),
                        "correction_pass_limit" to MAX_VERIFICATION_CORRECTION_PASSES,
                        "finding_count" to correctionFindings.size,
                        "finding_codes" to findingCodes.joinToString(","),
                        "research_gate_finding_codes" to researchQualityFindingCodes(researchPublicationGate.reasons)
                            .joinToString(","),
                        "excluded_claim_count" to excludedClaimCount,
                        "recovery_capability" to recoveryCapabilities,
                        "recovery_routes" to recoveryRoutes.joinToString(",") { it.taskIdPrefix },
                        "evidence_only" to recoveryRoutes.all { it.evidenceOnly },
                        "structured_output_repaired" to verification.structuredOutputRepaired,
                        "verification_response_status" to verificationResponseStatus(verification),
                    ),
                )
                when (correctionGoal?.status) {
                    AgentGoalStatus.QUEUED -> WorkerOutcome.CONTINUE
                    AgentGoalStatus.PAUSED,
                    AgentGoalStatus.CANCELLED,
                    AgentGoalStatus.FINALIZING,
                    null,
                    -> WorkerOutcome.DONE

                    else -> WorkerOutcome.FAIL
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val latest = findGoal(goal.id) ?: return WorkerOutcome.FAIL
            if (latest.status in setOf(AgentGoalStatus.PAUSED, AgentGoalStatus.CANCELLED)) return WorkerOutcome.DONE
            val statusCode = (error as? OpenRouterException)?.statusCode
            val descriptor = FailureClassifier.classify(
                error = error,
                statusCode = statusCode,
                goalId = goal.id,
                operationId = "verification",
                emptyModelOutput = error.message?.contains("model returned no usable text") == true,
            )
            val decision = ProviderRecoveryPolicy.decideWithDescriptor(
                descriptor = descriptor,
                currentModelId = latest.plannerModelId,
                routingStage = latest.routingStage,
                isFreeOnly = latest.freeOnly,
            )
            val message = error.toAgentFailureMessage("Verification failed before a result was committed.").take(1_000)
            diagnostics.error(
                event = "agent_verification_failed",
                throwable = error,
                fields = mapOf(
                    "goal_id" to goal.id,
                    "duration_ms" to (System.currentTimeMillis() - verificationStartedAt),
                    "http_status" to statusCode,
                    "failure_class" to descriptor.failureClass,
                    "recovery" to decision.action.name,
                    "recovery_model" to decision.nextModelId,
                ),
            )
            store.updateGoal(goal.id) { current ->
                if (current.status != AgentGoalStatus.VERIFYING) return@updateGoal current
                val waiting = decision.action == ProviderRecoveryAction.WAIT_FOR_CREDENTIAL
                val rateLimitFailure = (statusCode == 429)
                val switchingModels = decision.action in setOf(
                    ProviderRecoveryAction.SWITCH_TO_STABLE,
                    ProviderRecoveryAction.SWITCH_TO_FREE,
                    ProviderRecoveryAction.ESCALATE_TO_PAID,
                )

                var routedCurrent = current.recoverProviderRoute(
                    decision = decision,
                    failedRoute = ProviderRouteKind.PLANNER,
                )
                val updatedCooldowns = if (rateLimitFailure) {
                    routedCurrent.modelCooldowns + (current.plannerModelId to (System.currentTimeMillis() + 300_000L))
                } else {
                    routedCurrent.modelCooldowns
                }

                if (switchingModels && models.isNotEmpty()) {
                    val nextRoute = AgentModelSelector.choose(
                        models = models,
                        selectedModelId = if (decision.action == ProviderRecoveryAction.SWITCH_TO_FREE) {
                            ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID
                        } else null,
                        freeOnly = routedCurrent.freeOnly,
                        modelCooldowns = updatedCooldowns,
                        routingStage = routedCurrent.routingStage,
                    )
                    routedCurrent = routedCurrent.copy(plannerModelId = nextRoute.plannerModelId)
                }

                when (decision.action) {
                    ProviderRecoveryAction.WAIT_FOR_CREDENTIAL -> routedCurrent.copy(
                        status = AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                        modelCooldowns = updatedCooldowns,
                        events = appendEvent(routedCurrent.events, "${decision.explanation} Last verification error: $message"),
                        error = decision.explanation,
                    )
                    ProviderRecoveryAction.WAIT_FOR_NETWORK -> {
                        val finishedAt = System.currentTimeMillis()
                        routedCurrent.copy(
                            status = AgentGoalStatus.WAITING_FOR_NETWORK,
                            modelCooldowns = updatedCooldowns,
                            networkWaitStartedAt = finishedAt,
                            networkWaitReason = decision.explanation,
                            resumeStatusAfterNetwork = AgentGoalStatus.QUEUED,
                            nextRetryAt = finishedAt + 30_000L,
                            events = appendEvent(routedCurrent.events, decision.explanation),
                        )
                    }
                    ProviderRecoveryAction.SWITCH_TO_STABLE,
                    ProviderRecoveryAction.SWITCH_TO_FREE,
                    ProviderRecoveryAction.ESCALATE_TO_PAID,
                    ProviderRecoveryAction.RETRY_CURRENT_ROUTE,
                    ProviderRecoveryAction.LOCAL_REPAIR,
                    ProviderRecoveryAction.AFTER_MATERIAL_STRATEGY_CHANGE -> routedCurrent.copy(
                        status = AgentGoalStatus.QUEUED,
                        modelCooldowns = updatedCooldowns,
                        events = appendEvent(routedCurrent.events, "${decision.explanation} Last verification error: $message"),
                        error = null,
                    )
                    ProviderRecoveryAction.ROUTE_EXHAUSTED -> routedCurrent.copy(
                        status = AgentGoalStatus.BLOCKED,
                        modelCooldowns = updatedCooldowns,
                        events = appendEvent(routedCurrent.events, "${decision.explanation} Last verification error: $message"),
                        error = decision.explanation,
                    )
                    ProviderRecoveryAction.REJECTED -> routedCurrent.copy(
                        status = AgentGoalStatus.REJECTED,
                        events = appendEvent(routedCurrent.events, "${decision.explanation} The request was rejected."),
                        error = decision.explanation,
                    )
                    ProviderRecoveryAction.BLOCKED_NEEDS_ACTION -> routedCurrent.copy(
                        status = AgentGoalStatus.BLOCKED_NEEDS_ACTION,
                        events = appendEvent(routedCurrent.events, "${decision.explanation} Action required."),
                        error = decision.explanation,
                    )
                }
            }
            return when (decision.action) {
                ProviderRecoveryAction.LOCAL_REPAIR -> WorkerOutcome.RETRY
                ProviderRecoveryAction.WAIT_FOR_CREDENTIAL -> WorkerOutcome.DONE
                ProviderRecoveryAction.WAIT_FOR_NETWORK -> WorkerOutcome.DONE
                ProviderRecoveryAction.SWITCH_TO_STABLE,
                ProviderRecoveryAction.SWITCH_TO_FREE,
                ProviderRecoveryAction.ESCALATE_TO_PAID,
                ProviderRecoveryAction.AFTER_MATERIAL_STRATEGY_CHANGE,
                -> WorkerOutcome.CONTINUE
                ProviderRecoveryAction.RETRY_CURRENT_ROUTE -> WorkerOutcome.RETRY
                ProviderRecoveryAction.ROUTE_EXHAUSTED,
                ProviderRecoveryAction.REJECTED,
                ProviderRecoveryAction.BLOCKED_NEEDS_ACTION -> WorkerOutcome.DONE
            }
        }
    }

    private fun findGoal(goalId: String): AgentGoal? = store.loadSnapshot().goals.firstOrNull { it.id == goalId }

    private fun mergeConceptCandidates(
        existing: List<AgentConceptCandidate>,
        incoming: List<AgentConceptCandidate>,
    ): List<AgentConceptCandidate> = (existing + incoming)
        .associateBy { it.name.lowercase().trim() }
        .values
        .toList()

    companion object {
        private const val MAX_VERIFICATION_CORRECTION_PASSES = 3
    }
}
