package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Manages the durable two-phase research cycle advancement protocol.
 * Ensures idempotency and prevents duplicate work.
 */
class ResearchCycleManager(
    private val store: AgentStore,
    private val client: AgentOpenRouterClient,
    private val diagnostics: RuntimeDiagnostics
) {

    /**
     * Phase A: Prepare Recovery.
     * Persists the recovery intent and returns the plan.
     */
    suspend fun prepareRecovery(
        goal: AgentGoal,
        task: AgentTask,
        decision: ResearchRecoveryEngine.RecoveryDecision,
        inputFingerprint: String,
        ticket: TaskExecutionTicket
    ): RecoveryPlan {
        val idempotencyKey = "cycle-recovery:${goal.id}:${task.cycleId ?: "baseline"}:$inputFingerprint:${decision.tactic}"
        
        // Check if already prepared
        goal.recoveryPlans.firstOrNull { it.id == idempotencyKey || it.inputFingerprint == inputFingerprint && it.tactic == decision.tactic }?.let {
            return it
        }

        val planId = UUID.randomUUID().toString()
        val plan = RecoveryPlan(
            id = planId,
            kind = decision.kind,
            cycleId = task.cycleId ?: "baseline",
            taskId = task.id,
            diagnosis = decision.diagnosis,
            tactic = decision.tactic,
            inputFingerprint = inputFingerprint,
            status = RecoveryPlanStatus.PREPARED
        )

        store.updateGoalAtomic(goal.id, ticket) { current ->
            if (current.recoveryPlans.any { it.inputFingerprint == inputFingerprint && it.tactic == decision.tactic }) {
                current
            } else {
                current.copy(
                    status = AgentGoalStatus.RECOVERING,
                    recoveryPlans = current.recoveryPlans + plan,
                    activeRecoveryPlanId = planId,
                    events = appendEvent(current.events, "Stall detected: ${decision.diagnosis}. Selecting recovery tactic: ${decision.tactic}.")
                )
            }
        }
        
        diagnostics.info(
            event = "recovery_plan_prepared",
            component = "mission",
            fields = mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "diagnosis" to decision.diagnosis.name,
                "tactic" to decision.tactic.name,
                "kind" to decision.kind.name
            )
        )

        return plan
    }

    /**
     * Phase B: Generate Recovery Proposal.
     * Performs provider-assisted planning.
     */
    suspend fun generateProposal(
        apiKey: String,
        goal: AgentGoal,
        plan: RecoveryPlan,
        ticket: TaskExecutionTicket
    ): RecoveryProposal {
        val parentOperationId = "op-recovery-${plan.id}"
        val missionContext = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = ticket.workerId,
            taskId = plan.taskId,
            attemptId = ticket.attemptId,
            executionGeneration = ticket.generation,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.REQUEST_CONSTRUCTION,
            operation = MissionOperation.ADAPTIVE_RESEARCH_STRATEGY, // Reuse for now or add new
            parentOperationId = parentOperationId,
        )

        // Update status to GENERATING
        store.updateGoalAtomic(goal.id, ticket) { current ->
            current.copy(
                recoveryPlans = current.recoveryPlans.map { 
                    if (it.id == plan.id) it.copy(status = RecoveryPlanStatus.GENERATING) else it 
                }
            )
        }
        
        diagnostics.info(
            event = "recovery_plan_generation_started",
            component = "mission",
            fields = mapOf("goal_id" to goal.id, "plan_id" to plan.id)
        )

        try {
            val proposal = client.createRecoveryProposal(
                apiKey = apiKey,
                goal = goal,
                plan = plan,
                requestContext = missionContext
            )
            
            // Phase C (Partial): Ready to commit
            val proposalFp = FingerprintUtils.computeStrategyFingerprint(proposal.strategyJson)
            
            store.updateGoalAtomic(goal.id, ticket) { current ->
                current.copy(
                    recoveryPlans = current.recoveryPlans.map { 
                        if (it.id == plan.id) it.copy(
                            status = RecoveryPlanStatus.READY_TO_COMMIT,
                            durableProposal = proposal,
                            proposalFingerprint = proposalFp,
                            generationTimestamp = System.currentTimeMillis()
                        ) else it 
                    }
                )
            }
            
            diagnostics.info(
                event = "recovery_plan_generation_completed",
                component = "mission",
                fields = mapOf("goal_id" to goal.id, "plan_id" to plan.id)
            )
            
            return proposal
        } catch (e: Exception) {
            store.updateGoalAtomic(goal.id, ticket) { current ->
                current.copy(
                    recoveryPlans = current.recoveryPlans.map { 
                        if (it.id == plan.id) it.copy(status = RecoveryPlanStatus.EXHAUSTED, failureExplanation = e.message) else it 
                    }
                )
            }
            throw e
        }
    }

    /**
     * Phase C: Commit Recovery.
     */
    suspend fun commitRecovery(
        goal: AgentGoal,
        plan: RecoveryPlan,
        ticket: TaskExecutionTicket
    ): WorkerOutcome {
        val proposal = plan.durableProposal ?: return WorkerOutcome.FAIL
        val activeCycle = goal.researchCycles.firstOrNull { it.id == goal.activeResearchCycleId }
        
        if (activeCycle != null && !ResearchRecoveryEngine.isNovel(proposal, activeCycle)) {
            diagnostics.warning(
                event = "recovery_strategy_rejected_not_novel",
                component = "mission",
                fields = mapOf("goal_id" to goal.id, "plan_id" to plan.id)
            )
            store.updateGoalAtomic(goal.id, ticket) { current ->
                current.copy(
                    recoveryPlans = current.recoveryPlans.map { 
                        if (it.id == plan.id) it.copy(status = RecoveryPlanStatus.REJECTED_NOT_NOVEL) else it 
                    }
                )
            }
            return WorkerOutcome.RETRY // Try another tactic
        }

        return when (plan.kind) {
            RecoveryKind.TACTIC_PIVOT -> commitTacticPivot(goal, plan, proposal, ticket)
            RecoveryKind.CYCLE_ADVANCE -> commitCycleAdvance(goal, plan, proposal, ticket)
        }
    }

    private suspend fun commitTacticPivot(
        goal: AgentGoal,
        plan: RecoveryPlan,
        proposal: RecoveryProposal,
        ticket: TaskExecutionTicket
    ): WorkerOutcome {
        store.updateGoalAtomic(goal.id, ticket) { current ->
            val activeCycleId = current.activeResearchCycleId
            current.copy(
                status = AgentGoalStatus.RUNNING,
                activeRecoveryPlanId = null,
                recoveryPlans = current.recoveryPlans.map { 
                    if (it.id == plan.id) it.copy(status = RecoveryPlanStatus.COMMITTED) else it 
                },
                tasks = current.tasks.map { task ->
                    if (task.id == plan.taskId) {
                        task.copy(
                            lastTactic = plan.tactic.name,
                            activeResearchStrategyJson = proposal.strategyJson,
                            strategyFingerprint = plan.proposalFingerprint,
                            queryPortfolioFingerprint = FingerprintUtils.computeQueryPortfolioFingerprint(proposal.queryPortfolio),
                            attemptCount = 0 // Reset attempt count for new tactic
                        )
                    } else task
                },
                researchCycles = current.researchCycles.map { cycle ->
                    if (cycle.id == activeCycleId) {
                        val updatedSummary = (cycle.learningSummary ?: ResearchCycleLearningSummary()).let { summary ->
                            summary.copy(attemptedTactics = summary.attemptedTactics + plan.tactic)
                        }
                        cycle.copy(learningSummary = updatedSummary)
                    } else cycle
                },
                events = appendEvent(current.events, "Tactic pivot committed: ${plan.tactic}. Continuing research.")
            )
        }
        return WorkerOutcome.CONTINUE
    }

    private suspend fun commitCycleAdvance(
        goal: AgentGoal,
        plan: RecoveryPlan,
        proposal: RecoveryProposal,
        ticket: TaskExecutionTicket
    ): WorkerOutcome {
        val nextOrdinal = (goal.researchCycles.maxOfOrNull { it.ordinal } ?: 0) + 1
        val newCycleId = UUID.randomUUID().toString()
        val newRevisionId = UUID.randomUUID().toString()
        
        val learningSummary = ResearchCycleLearningSummary(
            attemptedTactics = listOf(plan.tactic),
            carriedForwardEvidenceIds = goal.evidence.filter { it.kind != AgentEvidenceKind.SYSTEM_EVENT }.map { it.id }
        )

        val revision = ObjectiveRevision(
            id = newRevisionId,
            ordinal = nextOrdinal,
            parentRevisionId = goal.objectiveRevisions.lastOrNull()?.id,
            rootObjectiveFingerprint = FingerprintUtils.computeRootObjectiveFingerprint(goal),
            operationalObjective = proposal.revisedObjective ?: goal.objective,
            revisionFingerprint = "todo", // Compute later
        ).let { it.copy(revisionFingerprint = FingerprintUtils.computeOperationalObjectiveFingerprint(it)) }

        val newCycle = ResearchCycle(
            id = newCycleId,
            ordinal = nextOrdinal,
            status = ResearchCycleStatus.ACTIVE,
            parentCycleId = goal.activeResearchCycleId,
            objectiveRevisionId = newRevisionId,
            triggerDiagnosis = plan.diagnosis,
            learningSummary = learningSummary,
            activatedAt = System.currentTimeMillis()
        )

        store.updateGoalAtomic(goal.id, ticket) { current ->
            current.copy(
                status = AgentGoalStatus.PLANNING, // New cycle needs planning
                activeResearchCycleId = newCycleId,
                activeRecoveryPlanId = null,
                recoveryPlans = current.recoveryPlans.map { 
                    if (it.id == plan.id) it.copy(status = RecoveryPlanStatus.COMMITTED) else it 
                },
                researchCycles = current.researchCycles.map { 
                    if (it.id == current.activeResearchCycleId) it.copy(status = ResearchCycleStatus.SUPERSEDED, supersededAt = System.currentTimeMillis()) else it
                } + newCycle,
                objectiveRevisions = current.objectiveRevisions + revision,
                events = appendEvent(current.events, "Research cycle advanced to generation $nextOrdinal. Revising operational objective and replanning.")
            )
        }
        
        diagnostics.info(
            event = "research_cycle_advanced",
            component = "mission",
            fields = mapOf(
                "goal_id" to goal.id,
                "old_cycle_id" to (goal.activeResearchCycleId ?: "none"),
                "new_cycle_id" to newCycleId,
                "ordinal" to nextOrdinal
            )
        )
        
        return WorkerOutcome.CONTINUE
    }
}
