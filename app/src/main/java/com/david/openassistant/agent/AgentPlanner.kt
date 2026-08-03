package com.david.openassistant.agent

import com.david.openassistant.agent.AgentApiSummary
import com.david.openassistant.agent.AgentAttempt
import com.david.openassistant.agent.AgentAttemptStatus
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentCapabilityRegistry
import com.david.openassistant.agent.AgentEvidence
import com.david.openassistant.agent.AgentEvidenceKind
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentPlanDraft
import com.david.openassistant.agent.AgentStore
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.agent.ProviderRecoveryAction
import com.david.openassistant.agent.ProviderRecoveryPolicy
import com.david.openassistant.agent.ProviderRouteKind
import com.david.openassistant.agent.WorkerOutcome
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.agent.AgentRoutingPolicy
import com.david.openassistant.agent.UsageSource
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.AgentModelSelector
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AgentPlanner(
    private val client: AgentOpenRouterClient,
    private val store: AgentStore,
    private val diagnostics: RuntimeDiagnostics
) {
    suspend fun plan(
        apiKey: String,
        goal: AgentGoal,
        ticket: PlanningTicket,
        models: List<OpenRouterModel> = emptyList(),
    ): WorkerOutcome {
        val startedAt = System.currentTimeMillis()
        
        // V37 PLANNING_START validation
        val startValidation = store.validateTicket(ticket)
        diagnostics.info(
            event = "ownership_validation_completed",
            component = "lease",
            fields = mapOf(
                "goal_id" to goal.id,
                "validation_stage" to "PLANNING_START",
                "outcome" to if (startValidation is TicketValidationResult.Valid) "PASS" else "FAIL",
                "reason_code" to (startValidation as? TicketValidationResult.Mismatch)?.reason
            )
        )
        if (startValidation !is TicketValidationResult.Valid) {
            return WorkerOutcome.FAIL
        }

        diagnostics.info(
            event = "planning_started",
            component = "mission",
            fields = mapOf("goal_id" to goal.id, "worker_id" to ticket.workerId)
        )
        
        val attempt = AgentAttempt(
            taskId = null,
            status = AgentAttemptStatus.RUNNING,
            startedAt = startedAt,
            modelId = goal.plannerModelId,
        )
        store.updateGoalAtomic(goal.id, ticket) { current ->
            current.copy(attempts = retainAttempts(current.attempts + attempt))
        }

        val parentOperationId = "op-plan-${UUID.randomUUID()}"
        val missionContext = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = ticket.workerId,
            taskId = null,
            attemptId = ticket.attemptId,
            executionGeneration = ticket.generation,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.CREATE_PLAN,
            parentOperationId = parentOperationId,
        )

        return try {
            val (plan, summary) = client.createPlan(
                apiKey = apiKey,
                modelId = AgentRoutingPolicy.guardModel(goal, goal.plannerModelId),
                goal = goal,
                freeOnly = goal.freeOnly,
                requestContext = missionContext,
            )
            currentCoroutineContext().ensureActive()
            val cycleId = goal.activeResearchCycleId ?: ResearchRecoveryEngine.generateCycleIdentity(goal.id, 1)
            val tasks = plan.tasks.mapIndexed { index, draft ->
                AgentCapabilityRegistry.requireAllowed(draft.capability)
                AgentTask(
                    id = draft.id,
                    cycleId = cycleId,
                    order = index,
                    title = draft.title,
                    instructions = draft.instructions,
                    capability = draft.capability,
                    dependsOn = draft.dependsOn,
                    status = AgentTaskStatus.QUEUED,
                    weight = draft.weight,
                    acceptanceCriteria = ConstraintValidator.filterGrounded(draft.acceptanceCriteria, goal.userRequest),
                )
            }
            val filteredGoalCriteria = ConstraintValidator.filterGrounded(plan.acceptanceCriteria, goal.userRequest)
            require(tasks.isNotEmpty()) { "The planner returned no executable milestones." }
            val planEvidence = AgentEvidence(
                id = "plan_evidence_${UUID.randomUUID()}",
                taskId = null,
                cycleId = cycleId,
                kind = AgentEvidenceKind.PLAN,
                title = "Validated durable plan",
                summary = "${tasks.size} measurable milestones and ${filteredGoalCriteria.size} final checks were created.",
                content = buildString {
                    appendLine("Goal acceptance criteria:")
                    filteredGoalCriteria.forEach { criterion ->
                        appendLine("- ${criterion.id}: ${criterion.description} (weight ${criterion.weight})")
                    }
                    appendLine()
                    appendLine("Execution milestones:")
                    tasks.forEach { task ->
                        appendLine("${task.order + 1}. ${task.title} [${task.capability.wireName}; weight ${task.weight}]")
                        task.acceptanceCriteria.forEach { criterion ->
                            appendLine("   - ${criterion.id}: ${criterion.description}")
                        }
                    }
                }.take(MAX_EVIDENCE_CONTENT_CHARS),
            )
            store.updateGoalAtomic(goal.id, ticket) { current ->
                if (current.status != AgentGoalStatus.PLANNING) return@updateGoalAtomic current
                val accountingKey = "plan_accounting_${attempt.id}"
                if (current.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) {
                    return@updateGoalAtomic current
                }

                var baseUpdated = current.withAdditionalUsage(summary.totalTokens, summary.costUsd)
                
                // V42.4: Establish explicit baseline cycle ownership during original plan commit
                if (baseUpdated.activeResearchCycleId == null) {
                    val revisionId = ResearchRecoveryEngine.generateRevisionIdentity(current.id, 1)
                    val rootFp = FingerprintUtils.calculateRootObjectiveFingerprint(current)
                    val strategyFp = FingerprintUtils.calculateStrategyFingerprint(plan.objective, emptyList(), null)
                    
                    val baselineRevision = ObjectiveRevision(
                        id = revisionId,
                        ordinal = 1,
                        parentRevisionId = null,
                        immutableRootObjectiveFingerprint = rootFp,
                        operationalObjective = plan.objective,
                        unresolvedGaps = emptyList(),
                        retainedConstraints = current.confirmedConstraints,
                        evidenceRequirements = current.evidenceRequirements,
                        revisionReason = "Initial baseline revision.",
                        revisionFingerprint = rootFp
                    )
                    val baselineCycle = ResearchCycle(
                        id = cycleId,
                        ordinal = 1,
                        parentCycleId = null,
                        status = ResearchCycleStatus.ACTIVE,
                        objectiveRevisionId = revisionId,
                        triggerDiagnosis = ExecutionStallDiagnosis.NONE,
                        selectedAdvancementTactic = EscalationTactic.NONE,
                        strategyFingerprint = strategyFp,
                        queryPortfolioFingerprint = FingerprintUtils.hash("v1:portfolio:empty"),
                        acceptedEvidenceFingerprint = FingerprintUtils.hash("v1:accepted_evidence:empty"),
                        unresolvedGapFingerprint = FingerprintUtils.hash("v1:unresolved_gap:initial"),
                        learningSummary = null,
                        activatedAt = baseUpdated.createdAt
                    )
                    baseUpdated = baseUpdated.copy(
                        researchCycles = baseUpdated.researchCycles + baselineCycle,
                        objectiveRevisions = baseUpdated.objectiveRevisions + baselineRevision,
                        activeResearchCycleId = cycleId
                    )
                }

                val accountingRecord = IdempotencyRecord(
                    key = accountingKey,
                    effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
                    state = IdempotencyState.COMMITTED,
                    claimOwner = ticket.workerId,
                    committedAt = System.currentTimeMillis(),
                    completedBy = ticket.workerId
                )

                baseUpdated.copy(
                    title = plan.title.ifBlank { current.title },
                    objective = plan.objective,
                    finalOutputDescription = plan.finalOutputDescription,
                    objectiveContract = plan.objectiveContract,
                    status = AgentGoalStatus.QUEUED,
                    tasks = tasks,
                    acceptanceCriteria = filteredGoalCriteria,
                    evidence = appendEvidence(baseUpdated.evidence, planEvidence),
                    idempotencyRecords = baseUpdated.idempotencyRecords + accountingRecord,
                    attempts = current.attempts.map { existing ->
                        if (existing.id == attempt.id) {
                            existing.copy(
                                status = AgentAttemptStatus.SUCCEEDED,
                                finishedAt = System.currentTimeMillis(),
                                resolvedModel = summary.resolvedModel,
                                role = summary.role,
                                selectionReason = summary.selectionReason,
                                previousRoute = summary.previousRoute,
                                cooldownState = summary.cooldownState,
                                provider = summary.provider,
                                responseId = summary.responseId,
                                promptTokens = summary.promptTokens,
                                completionTokens = summary.completionTokens,
                                totalTokens = summary.totalTokens,
                                costUsd = summary.costUsd,
                                webSearchRequests = summary.webSearchRequests,
                            )
                        } else {
                            existing
                        }
                    },
                    events = appendEvent(
                        current.events,
                        "Plan validated with ${tasks.size} measurable milestones. Execution will continue until verified completion, explicit cancellation, or a credential wait state.",
                    ),
                    error = null,
                )
            }
            
            diagnostics.info(
                event = "initial_cycle_binding_committed",
                component = "mission",
                fields = mapOf(
                    "goal_id" to goal.id,
                    "cycle_id" to (goal.activeResearchCycleId ?: "none"),
                    "task_count" to tasks.size
                )
            )

            diagnostics.info(
                event = "planning_completed",
                component = "mission",
                fields = mapOf(
                    "goal_id" to goal.id,
                    "duration_ms" to (System.currentTimeMillis() - startedAt),
                    "task_count" to tasks.size
                )
            )
            WorkerOutcome.CONTINUE
        } catch (error: CancellationException) {
            store.updateGoalAtomic(goal.id, ticket) { current ->
                current.copy(
                    attempts = current.attempts.map { existing ->
                        if (existing.id == attempt.id && existing.status == AgentAttemptStatus.RUNNING) {
                            existing.copy(
                                status = AgentAttemptStatus.FAILED,
                                finishedAt = System.currentTimeMillis(),
                                error = "Planning attempt cancelled",
                            )
                        } else existing
                    }
                )
            }
            throw error
        } catch (error: Throwable) {
            persistPlanningFailure(goal.id, attempt.id, error, ticket, models)
        }
    }

    suspend fun generateRecoveryProposal(
        apiKey: String,
        goal: AgentGoal,
        plan: ResearchRecoveryPlan,
        ticket: PlanningTicket,
    ): WorkerOutcome {
        val parentOperationId = "op-recovery-${plan.id}"
        val missionContext = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = ticket.workerId,
            taskId = plan.taskId,
            attemptId = ticket.attemptId,
            executionGeneration = ticket.generation,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = if (plan.selectedTactic == EscalationTactic.CYCLE_ADVANCE) MissionOperation.CYCLE_ADVANCE else MissionOperation.RECOVERY_PROPOSAL,
            parentOperationId = parentOperationId,
        )

        return try {
            val (proposal, summary) = if (plan.selectedTactic == EscalationTactic.CYCLE_ADVANCE) {
                val activeCycle = goal.researchCycles.firstOrNull { it.id == goal.activeResearchCycleId }
                    ?: throw IllegalStateException("Active cycle missing for cycle advancement.")
                val learningSummary = activeCycle.learningSummary
                    ?: throw IllegalStateException("Learning summary missing for cycle advancement.")
                
                val (draft, s) = client.createCycleAdvancementProposal(
                    apiKey = apiKey,
                    modelId = AgentRoutingPolicy.guardModel(goal, goal.plannerModelId),
                    goal = goal,
                    sourceCycle = activeCycle,
                    learningSummary = learningSummary,
                    freeOnly = goal.freeOnly,
                    requestContext = missionContext,
                )
                
                val p = RecoveryProposal(
                    revisedInvestigationInterpretation = draft.objective,
                    specificUnresolvedGap = draft.tasks.joinToString("; ") { it.title },
                    selectedSourceFamilyShift = null,
                    evidenceTargets = draft.acceptanceCriteria.map { it.description },
                    falsifiers = emptyList(),
                    newQueryPortfolio = emptyList(),
                    followUpRule = null,
                    rationale = "Generated successor cycle proposal.",
                    expectedNoveltyDimensions = listOf("objective", "tasks")
                )
                p to s
            } else {
                client.createResearchRecoveryProposal(
                    apiKey = apiKey,
                    modelId = AgentRoutingPolicy.guardModel(goal, goal.plannerModelId),
                    goal = goal,
                    plan = plan,
                    evidence = goal.evidence,
                    freeOnly = goal.freeOnly,
                    requestContext = missionContext,
                )
            }

            currentCoroutineContext().ensureActive()

            val proposalFingerprint = FingerprintUtils.calculateProposalFingerprint(proposal)
            
            // Pre-calculate authorized retry fingerprint for tactic pivot
            val retryFp = if (plan.selectedTactic != EscalationTactic.CYCLE_ADVANCE) {
                val simulatedTask = (goal.tasks.firstOrNull { it.id == plan.taskId } ?: throw IllegalStateException("Task missing")).copy(
                    lastRecoveryStrategy = proposal.revisedInvestigationInterpretation,
                    recoveryStrategyFingerprint = FingerprintUtils.calculateStrategyFingerprint(
                        proposal.revisedInvestigationInterpretation,
                        proposal.evidenceTargets,
                        proposal.selectedSourceFamilyShift
                    ),
                    resultSetFingerprint = FingerprintUtils.calculatePortfolioFingerprint(proposal.newQueryPortfolio)
                )
                FingerprintUtils.calculateExecutionFingerprint(goal, simulatedTask)
            } else null

            // Novelty validation
            if (!ResearchRecoveryEngine.validateNovelty(proposal, goal.recoveryPlans)) {
                store.updateGoalAtomic(goal.id, ticket) { current ->
                    current.copy(
                        recoveryPlans = current.recoveryPlans.map { p ->
                            if (p.id == plan.id) p.copy(
                                status = RecoveryPlanStatus.REJECTED_NOT_NOVEL,
                                proposal = proposal,
                                proposalFingerprint = proposalFingerprint,
                                generatedAt = System.currentTimeMillis()
                            ) else p
                        },
                        events = appendEvent(current.events, "Recovery proposal rejected: not materially novel.")
                    )
                }
                return WorkerOutcome.DONE
            }

            store.updateGoalAtomic(goal.id, ticket) { current ->
                current.copy(
                    recoveryPlans = current.recoveryPlans.map { p ->
                        if (p.id == plan.id) p.copy(
                            status = RecoveryPlanStatus.READY_TO_COMMIT,
                            proposal = proposal,
                            proposalFingerprint = proposalFingerprint,
                            accountingSummary = summary,
                            retryAuthorizedFingerprint = retryFp,
                            generatedAt = System.currentTimeMillis()
                        ) else p
                    }
                )
            }
            WorkerOutcome.CONTINUE
        } catch (error: Throwable) {
            persistRecoveryFailure(goal.id, plan.id, error, ticket)
        }
    }

    fun commitRecoveryEffect(
        goal: AgentGoal,
        plan: ResearchRecoveryPlan,
        ticket: PlanningTicket,
    ): WorkerOutcome {
        val proposal = plan.proposal ?: return WorkerOutcome.FAIL
        
        return if (plan.selectedTactic == EscalationTactic.CYCLE_ADVANCE) {
            commitCycleAdvance(goal, plan, proposal, ticket)
        } else {
            commitTacticPivot(goal, plan, proposal, ticket)
        }
    }

    private fun commitTacticPivot(
        goal: AgentGoal,
        plan: ResearchRecoveryPlan,
        proposal: RecoveryProposal,
        ticket: PlanningTicket,
    ): WorkerOutcome {
        val taskId = plan.taskId
        
        val strategyFp = FingerprintUtils.calculateStrategyFingerprint(
            proposal.revisedInvestigationInterpretation,
            proposal.evidenceTargets,
            proposal.selectedSourceFamilyShift
        )
        val portfolioFp = FingerprintUtils.calculatePortfolioFingerprint(proposal.newQueryPortfolio)

        store.updateGoalAtomic(goal.id, ticket) { current ->
            val accountingKey = "recovery_accounting_${plan.id}"
            if (current.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) {
                return@updateGoalAtomic current
            }

            val accountingRecord = IdempotencyRecord(
                key = accountingKey,
                effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
                state = IdempotencyState.COMMITTED,
                claimOwner = ticket.workerId,
                committedAt = System.currentTimeMillis(),
                completedBy = ticket.workerId
            )

            current.copy(
                tasks = current.tasks.map { task ->
                    if (task.id == taskId) {
                        task.copy(
                            status = AgentTaskStatus.QUEUED,
                            lastRecoveryStrategy = proposal.revisedInvestigationInterpretation,
                            recoveryStrategyFingerprint = strategyFp,
                            resultSetFingerprint = portfolioFp,
                            retryAuthorizedFingerprint = plan.retryAuthorizedFingerprint,
                            lastTactic = plan.selectedTactic.name,
                            activeResearchStrategyJson = null,
                            lastError = null,
                            failureClass = null
                        )
                    } else task
                },
                recoveryPlans = current.recoveryPlans.map { p ->
                    if (p.id == plan.id) p.copy(status = RecoveryPlanStatus.COMMITTED, committedAt = System.currentTimeMillis())
                    else p
                },
                idempotencyRecords = current.idempotencyRecords + accountingRecord,
                activeRecoveryPlanId = null,
                events = appendEvent(current.events, "Tactic pivot committed: ${plan.selectedTactic.name}.")
            ).withAdditionalUsage(plan.accountingSummary?.totalTokens, plan.accountingSummary?.costUsd)
        }
        return WorkerOutcome.CONTINUE
    }

    private fun commitCycleAdvance(
        goal: AgentGoal,
        plan: ResearchRecoveryPlan,
        proposal: RecoveryProposal,
        ticket: PlanningTicket,
    ): WorkerOutcome {
        val activeCycleId = goal.activeResearchCycleId ?: return WorkerOutcome.FAIL
        val activeCycle = goal.researchCycles.firstOrNull { it.id == activeCycleId } ?: return WorkerOutcome.FAIL
        
        val nextCycleOrdinal = activeCycle.ordinal + 1
        val nextRevisionOrdinal = (goal.objectiveRevisions.maxOfOrNull { it.ordinal } ?: 1) + 1
        
        val nextRevisionId = ResearchRecoveryEngine.generateRevisionIdentity(goal.id, nextRevisionOrdinal)
        val nextCycleId = ResearchRecoveryEngine.generateCycleIdentity(goal.id, nextCycleOrdinal)

        val rootFp = FingerprintUtils.calculateRootObjectiveFingerprint(goal)
        val strategyFp = FingerprintUtils.calculateStrategyFingerprint(proposal.revisedInvestigationInterpretation, proposal.evidenceTargets, null)
        val portfolioFp = FingerprintUtils.calculatePortfolioFingerprint(proposal.newQueryPortfolio)

        val nextRevision = ObjectiveRevision(
            id = nextRevisionId,
            ordinal = nextRevisionOrdinal,
            parentRevisionId = goal.objectiveRevisions.lastOrNull()?.id,
            immutableRootObjectiveFingerprint = rootFp,
            operationalObjective = proposal.revisedInvestigationInterpretation,
            unresolvedGaps = listOf(proposal.specificUnresolvedGap),
            retainedConstraints = goal.confirmedConstraints,
            evidenceRequirements = goal.evidenceRequirements,
            revisionReason = "Cycle advancement recovery.",
            revisionFingerprint = strategyFp
        )

        val carryForwardIds = activeCycle.learningSummary?.carryForwardEvidenceIds.orEmpty()
        val nextCycle = ResearchCycle(
            id = nextCycleId,
            ordinal = nextCycleOrdinal,
            parentCycleId = activeCycle.id,
            status = ResearchCycleStatus.ACTIVE,
            objectiveRevisionId = nextRevisionId,
            triggerDiagnosis = activeCycle.triggerDiagnosis,
            selectedAdvancementTactic = EscalationTactic.CYCLE_ADVANCE,
            strategyFingerprint = strategyFp,
            queryPortfolioFingerprint = portfolioFp,
            acceptedEvidenceFingerprint = FingerprintUtils.hash("v1:accepted_evidence:" + carryForwardIds.sorted().joinToString("|")),
            unresolvedGapFingerprint = FingerprintUtils.hash("v1:unresolved_gap:successor"),
            learningSummary = null,
            activatedAt = System.currentTimeMillis()
        )

        store.updateGoalAtomic(goal.id, ticket) { current ->
            if (current.activeResearchCycleId != activeCycleId) return@updateGoalAtomic current
            val accountingKey = "recovery_accounting_${plan.id}"
            if (current.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) {
                return@updateGoalAtomic current
            }

            val accountingRecord = IdempotencyRecord(
                key = accountingKey,
                effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
                state = IdempotencyState.COMMITTED,
                claimOwner = ticket.workerId,
                committedAt = System.currentTimeMillis(),
                completedBy = ticket.workerId
            )
            
            val updatedCycles = current.researchCycles.map {
                if (it.id == activeCycleId) it.copy(status = ResearchCycleStatus.SUPERSEDED, supersededAt = System.currentTimeMillis())
                else it
            } + nextCycle

            val successorTasks = listOf(
                AgentTask(
                    id = "recovery_task_" + FingerprintUtils.hash("v1:recovery_task:" + plan.id).take(16),
                    cycleId = nextCycleId,
                    order = 0,
                    title = "Successor Research Pass",
                    instructions = proposal.specificUnresolvedGap,
                    capability = AgentCapability.DEEP_RESEARCH,
                    status = AgentTaskStatus.QUEUED,
                    weight = 1.0,
                    acceptanceCriteria = proposal.evidenceTargets.mapIndexed { i, desc -> 
                        AgentAcceptanceCriterion("gap_$i", desc) 
                    }.ifEmpty { listOf(AgentAcceptanceCriterion("gap_0", "Materially resolve the identified gap.")) }
                )
            )

            current.copy(
                researchCycles = updatedCycles,
                objectiveRevisions = current.objectiveRevisions + nextRevision,
                activeResearchCycleId = nextCycleId,
                tasks = current.tasks.map { it.copy(status = AgentTaskStatus.CANCELLED) } + successorTasks,
                recoveryPlans = current.recoveryPlans.map { p ->
                    if (p.id == plan.id) p.copy(status = RecoveryPlanStatus.COMMITTED, committedAt = System.currentTimeMillis())
                    else p
                },
                idempotencyRecords = current.idempotencyRecords + accountingRecord,
                activeRecoveryPlanId = null,
                events = appendEvent(current.events, "Cycle $nextCycleOrdinal advanced with refined objective."),
                error = null
            ).withAdditionalUsage(plan.accountingSummary?.totalTokens, plan.accountingSummary?.costUsd)
        }
        return WorkerOutcome.CONTINUE
    }

    private fun persistRecoveryFailure(
        goalId: String,
        planId: String,
        error: Throwable,
        ticket: PlanningTicket,
    ): WorkerOutcome {
        val statusCode = (error as? OpenRouterException)?.statusCode
        val failureClass = if (error is IOException) "network_wait" else error::class.java.simpleName
        
        store.updateGoalAtomic(goalId, ticket) { current ->
            current.copy(
                recoveryPlans = current.recoveryPlans.map { p ->
                    if (p.id == planId) p.copy(
                        status = if (statusCode == 429 || error is IOException) RecoveryPlanStatus.FAILED_RETRYABLE else RecoveryPlanStatus.FAILED_NEEDS_ACTION,
                        failureClassification = failureClass,
                        failureMessage = error.message?.take(1000)
                    ) else p
                },
                events = appendEvent(current.events, "Recovery generation failed: $failureClass. ${error.message?.take(200)}")
            )
        }
        return if (statusCode == 429 || error is IOException) WorkerOutcome.RETRY else WorkerOutcome.FAIL
    }

    private fun persistPlanningFailure(
        goalId: String,
        attemptId: String,
        error: Throwable,
        ticket: PlanningTicket,
        models: List<OpenRouterModel> = emptyList(),
    ): WorkerOutcome {
        val latest = store.loadSnapshot().goals.firstOrNull { it.id == goalId } ?: return WorkerOutcome.FAIL
        if (latest.status != AgentGoalStatus.PLANNING) return WorkerOutcome.DONE
        val statusCode = (error as? OpenRouterException)?.statusCode
        val failureDescriptor = FailureClassifier.classify(
            error = error,
            statusCode = statusCode,
            goalId = goalId,
            operationId = "plan_generation",
        )
        val decision = ProviderRecoveryPolicy.decideWithDescriptor(
            descriptor = failureDescriptor,
            currentModelId = latest.plannerModelId,
            routingStage = latest.routingStage,
            isFreeOnly = latest.freeOnly,
        )
        val message = error.toAgentFailureMessage("Planning failed before a durable plan was committed.").take(1_000)
        val finishedAt = System.currentTimeMillis()
        
        store.updateGoalAtomic(goalId, ticket) { current ->
            if (current.status != AgentGoalStatus.PLANNING) return@updateGoalAtomic current
            val accountedCurrent = current.accountPlanningFailureUsage(error)
            val switchingModels = decision.action in setOf(
                ProviderRecoveryAction.SWITCH_TO_STABLE,
                ProviderRecoveryAction.SWITCH_TO_FREE,
                ProviderRecoveryAction.ESCALATE_TO_PAID,
            )

            var routedCurrent = accountedCurrent.recoverProviderRoute(
                decision = decision,
                failedRoute = ProviderRouteKind.PLANNER,
            )
            val rateLimitFailure = (statusCode == 429)
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

            val failureUsage = error as? OpenRouterException
            val updatedAttempts = routedCurrent.attempts.map { existing ->
                if (existing.id == attemptId) {
                    existing.copy(
                        status = AgentAttemptStatus.FAILED,
                        finishedAt = finishedAt,
                        role = failureUsage?.role,
                        selectionReason = failureUsage?.selectionReason,
                        previousRoute = failureUsage?.previousRoute,
                        cooldownState = failureUsage?.cooldownState,
                        provider = failureUsage?.provider,
                        promptTokens = failureUsage?.promptTokens,
                        completionTokens = failureUsage?.completionTokens,
                        totalTokens = failureUsage?.totalTokens,
                        costUsd = failureUsage?.costUsd,
                        webSearchRequests = failureUsage?.webSearchRequests,
                        error = message,
                    )
                } else {
                    existing
                }
            }
            when (decision.action) {
                ProviderRecoveryAction.WAIT_FOR_CREDENTIAL -> routedCurrent.copy(
                    status = AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                    attempts = updatedAttempts,
                    modelCooldowns = updatedCooldowns,
                    events = appendEvent(routedCurrent.events, "${decision.explanation} Last error: $message"),
                    error = decision.explanation,
                )
                ProviderRecoveryAction.WAIT_FOR_NETWORK -> routedCurrent.copy(
                    status = AgentGoalStatus.WAITING_FOR_NETWORK,
                    attempts = updatedAttempts,
                    modelCooldowns = updatedCooldowns,
                    networkWaitStartedAt = finishedAt,
                    networkWaitReason = decision.explanation,
                    resumeStatusAfterNetwork = AgentGoalStatus.PLANNING,
                    nextRetryAt = finishedAt + 30_000L,
                    events = appendEvent(routedCurrent.events, decision.explanation),
                )
                ProviderRecoveryAction.SWITCH_TO_STABLE,
                ProviderRecoveryAction.SWITCH_TO_FREE,
                ProviderRecoveryAction.ESCALATE_TO_PAID,
                ProviderRecoveryAction.LOCAL_REPAIR,
                ProviderRecoveryAction.AFTER_MATERIAL_STRATEGY_CHANGE,
                -> routedCurrent.copy(
                    attempts = updatedAttempts,
                    modelCooldowns = updatedCooldowns,
                    events = appendEvent(routedCurrent.events, "${decision.explanation} Planning will continue automatically."),
                    error = message,
                )
                ProviderRecoveryAction.RETRY_CURRENT_ROUTE -> routedCurrent.copy(
                    attempts = updatedAttempts,
                    modelCooldowns = updatedCooldowns,
                    events = appendEvent(routedCurrent.events, "${decision.explanation} Last error: $message"),
                    error = message,
                )
                ProviderRecoveryAction.ROUTE_EXHAUSTED -> routedCurrent.copy(
                    status = AgentGoalStatus.BLOCKED,
                    attempts = updatedAttempts,
                    modelCooldowns = updatedCooldowns,
                    events = appendEvent(routedCurrent.events, "${decision.explanation} Last error: $message"),
                    error = decision.explanation,
                )
                ProviderRecoveryAction.REJECTED -> routedCurrent.copy(
                    status = AgentGoalStatus.REJECTED,
                    attempts = updatedAttempts,
                    events = appendEvent(routedCurrent.events, "${decision.explanation} The request was rejected."),
                    error = decision.explanation,
                )
                ProviderRecoveryAction.BLOCKED_NEEDS_ACTION -> routedCurrent.copy(
                    status = AgentGoalStatus.BLOCKED_NEEDS_ACTION,
                    attempts = updatedAttempts,
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
