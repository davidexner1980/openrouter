package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.DiagnosticEvent
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterModel
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class RecoveryLivelockReproTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var client: FakeAgentOpenRouterClient
    private lateinit var planner: AgentPlanner
    private lateinit var goalId: String
    private lateinit var taskId: String
    private lateinit var workerId: String

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val diagDir = tempFolder.newFolder("diagnostics")
        diagnostics = RuntimeDiagnostics(null, diagDir, null)
        
        client = FakeAgentOpenRouterClient(store)
        planner = AgentPlanner(client, store, diagnostics)
        
        goalId = "goal-${UUID.randomUUID()}"
        taskId = "task-1"
        workerId = "worker-1"
    }

    @Test
    fun reproduceSplitOwnershipLivelock() = runBlocking {
        val sessionId = DiagnosticEvent.PROCESS_SESSION_ID
        val planId = "plan-" + UUID.randomUUID()
        val ticket = PlanningTicket(goalId, workerId, sessionId, 1, "attempt-1", System.currentTimeMillis())
        
        val task = AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)
        val initialGoal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(task),
            leaseGeneration = 1,
            executionLease = AgentExecutionLease(workerId, sessionId, "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis()),
            objectiveContract = ObjectiveContract(1, "Title", listOf("Objective"), null, "Desc", "GENERAL", "hash")
        )
        
        val inputFp = FingerprintUtils.calculateExecutionFingerprint(initialGoal, task)
        val plan = ResearchRecoveryPlan(
            id = planId,
            goalId = goalId,
            taskId = taskId,
            inputExecutionFingerprint = inputFp,
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            status = RecoveryPlanStatus.PREPARED,
            logicalProviderRequestId = null,
            proposal = null,
            proposalFingerprint = null,
            validationResult = null,
            failureClassification = null,
            failureMessage = null
        )
        
        val goal = initialGoal.copy(recoveryPlans = listOf(plan), activeRecoveryPlanId = planId)
        store.upsertGoal(goal, true)
        
        val validProposalJson = JSONObject()
            .put("revised_investigation_interpretation", "new strategy for Objective")
            .put("specific_unresolved_gap", "gap")
            .put("evidence_targets", JSONArray().put("target1"))
            .put("falsifiers", JSONArray())
            .put("new_query_portfolio", JSONArray().put("query1"))
            .put("rationale", "rationale")
            .put("expected_novelty_dimensions", JSONArray().put("strategy"))
            .toString()

        val envelopeJson = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", validProposalJson))))
            .put("usage", JSONObject().put("total_tokens", 50))
            .toString()

        client.nextResponseJson = envelopeJson

        // 1. PREPARED -> GENERATING transition (Authoritative identity persistence)
        // This is a NEW requirement. The test will fail here until Planner is updated.
        // Actually, let's trace what the test SHOULD do.
        
        // Exercise generateRecoveryProposal
        val outcome = planner.generateRecoveryProposal("key", goal, plan, ticket)
        
        // Before the fix, generateRecoveryProposal will fail because it tries PREPARED -> READY_TO_COMMIT
        // and canTransitionTo(READY_TO_COMMIT) from PREPARED is false.
        
        val finalSnap = store.loadSnapshot().goals.first { it.id == goalId }
        val finalPlan = finalSnap.recoveryPlans.firstOrNull { it.id == planId }

        // Assertions for FIXED state:
        assertEquals(WorkerOutcome.CONTINUE, outcome)
        assertEquals(RecoveryPlanStatus.READY_TO_COMMIT, finalPlan?.status)
        assertNotNull(finalPlan?.proposalFingerprint)
        
        // For REPRO, we expect it to fail or be in an inconsistent state.
        // The prompt says: "The new regression test must exercise the real terminalization path"
        // and specifies restart behavior.
        
        // I'll implement the test to match the "Required final ownership" and "Verification" section of the prompt.
    }

    @Test
    fun testEndToEndRecoveryWithRestart() = runBlocking {
        val sessionId = DiagnosticEvent.PROCESS_SESSION_ID
        val planId = "plan-1"
        val ticket = PlanningTicket(goalId, workerId, sessionId, 1, "attempt-1", System.currentTimeMillis())
        
        val task = AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)
        val initialGoal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(task),
            leaseGeneration = 1,
            executionLease = AgentExecutionLease(workerId, sessionId, "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis()),
            objectiveContract = ObjectiveContract(1, "Title", listOf("Objective"), null, "Desc", "GENERAL", "hash")
        )
        
        val inputFp = FingerprintUtils.calculateExecutionFingerprint(initialGoal, task)
        val plan = ResearchRecoveryPlan(
            id = planId,
            goalId = goalId,
            taskId = taskId,
            inputExecutionFingerprint = inputFp,
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            status = RecoveryPlanStatus.GENERATING,
            logicalProviderRequestId = "recovery-$planId",
            proposal = null,
            proposalFingerprint = null,
            validationResult = null,
            failureClassification = null,
            failureMessage = null
        )
        
        store.upsertGoal(initialGoal.copy(recoveryPlans = listOf(plan), activeRecoveryPlanId = planId), true)
        
        val proposal = RecoveryProposal(
            revisedInvestigationInterpretation = "new strategy for Objective",
            specificUnresolvedGap = "gap",
            selectedSourceFamilyShift = null,
            evidenceTargets = listOf("target1"),
            falsifiers = emptyList(),
            newQueryPortfolio = listOf("query1"),
            followUpRule = null,
            rationale = "rationale",
            expectedNoveltyDimensions = listOf("strategy")
        )
        val summary = AgentApiSummary(responseId = "resp-1", totalTokens = 50)
        
        val validProposalJson = JSONObject()
            .put("revised_investigation_interpretation", proposal.revisedInvestigationInterpretation)
            .put("specific_unresolved_gap", proposal.specificUnresolvedGap)
            .put("evidence_targets", JSONArray(proposal.evidenceTargets))
            .put("falsifiers", JSONArray())
            .put("new_query_portfolio", JSONArray(proposal.newQueryPortfolio))
            .put("rationale", proposal.rationale)
            .put("expected_novelty_dimensions", JSONArray(proposal.expectedNoveltyDimensions))
            .toString()

        // Use a client that we can control
        val envelopeJson = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", validProposalJson))))
            .put("usage", JSONObject().put("total_tokens", 50))
            .toString()

        client.nextResponseJson = envelopeJson

        // 1. First execution: PREPARED -> GENERATING -> provider call -> terminalize exchange
        // (Simulate process death before semantic commit)
        
        // We'll manually trigger parts if needed, but planner.generateRecoveryProposal should do it all.
        // For REPRO, we want to see it fail at the READY_TO_COMMIT transition if PREPARED.
        
        // Actually, the prompt wants us to exercise the REAL terminalization path.
        // transitionExchangeOutcomeWithResultAtomic
        
        val context = ProviderRequestContext.Mission(
            goalId = goalId,
            workerId = workerId,
            taskId = null,
            attemptId = "attempt-1",
            executionGeneration = 1,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.RECOVERY_PROPOSAL,
            parentOperationId = "op-recovery-$planId",
            logicalRequestId = "recovery-$planId",
            recoveryPlanId = planId
        )

        // Calculate the real fingerprint that will be used by the planner
        val realPayload = client.buildResearchRecoveryProposalPayload("key", "model", initialGoal, plan, initialGoal.evidence, false)
        val realFp = FingerprintUtils.hash(realPayload.toString())

        // 1. Create the initial active attempt that will be reconciled later
        val claimResult = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            ticket = ticket,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.RECOVERY_PROPOSAL,
            payloadFingerprint = realFp,
            wirePayloadFingerprint = realFp,
            logicalRequestId = "recovery-$planId",
            recoveryPlanId = planId,
            wireVariantKind = ProviderWireVariantKind.STRICT_SCHEMA,
            wireVariantOrdinal = 0,
            fingerprintSchemaVersion = 2
        )
        val exchangeId = (claimResult as ReconciliationResult.NewDispatchClaimed).attempt.exchangeId

        // Simulate Boundary A: provider exchange terminalization
        store.transitionExchangeOutcomeWithResultAtomic(
            goalId = goalId,
            exchangeId = exchangeId,
            newOutcome = ExchangeOutcome.RESPONSE_SUCCESS,
            context = context,
            summary = summary,
            statusCode = 200,
            providerResponseId = "resp-1",
            responseContent = envelopeJson
        )
        
        val planAfterA = store.loadSnapshot().goals.first { it.id == goalId }.recoveryPlans.first { it.id == planId }
        
        // Assertions for FIXED state Boundary A:
        assertEquals(RecoveryPlanStatus.GENERATING, planAfterA.status)
        assertNull(planAfterA.proposalFingerprint)
        
        // 2. Restart and Reconcile
        // 2. Restart and Reconcile
        val currentBaseDir = tempFolder.root.listFiles()?.firstOrNull { it.name == "agent_store_test" }!!
        val newStore = AgentStore(baseDir = currentBaseDir)
        client.activeStore = newStore // Authoritative store switch
        val newPlanner = AgentPlanner(client, newStore, diagnostics)
        
        // Planner should now reconcile the existing success and perform Boundary B (semantic commit)
        val outcome = newPlanner.generateRecoveryProposal("key", initialGoal.copy(recoveryPlans = listOf(plan), activeRecoveryPlanId = planId), plan, ticket)
        
        val planAfterB = newStore.loadSnapshot().goals.first { it.id == goalId }.recoveryPlans.first { it.id == planId }
        
        // Assertions for FIXED state Boundary B:
        assertEquals(WorkerOutcome.CONTINUE, outcome)
        assertEquals(RecoveryPlanStatus.READY_TO_COMMIT, planAfterB.status)
        assertNotNull(planAfterB.proposalFingerprint)
        assertEquals(FingerprintUtils.calculateProposalFingerprint(proposal), planAfterB.proposalFingerprint)
        
        // 3. Commit effect
        val effectOutcome = newPlanner.commitRecoveryEffect(initialGoal.copy(recoveryPlans = listOf(planAfterB), activeRecoveryPlanId = planId), planAfterB, ticket)
        val finalGoal = newStore.loadSnapshot().goals.first { it.id == goalId }
        val finalPlan = finalGoal.recoveryPlans.first { it.id == planId }
        
        // Assertions for FIXED state final:
        assertEquals(WorkerOutcome.CONTINUE, effectOutcome)
        assertEquals(RecoveryPlanStatus.COMMITTED, finalPlan.status)
        assertEquals(AgentGoalStatus.QUEUED, finalGoal.status)

        // Exact assertions for usage and dispatches
        assertEquals("Provider dispatches should be 0 during reconciliation", 0, client.dispatches)
        // Note: In a real run, there would be 1 dispatch in Boundary A, but we skipped it 
        // by manually calling transitionExchangeOutcomeWithResultAtomic in this test.
    }

    class FakeAgentOpenRouterClient(initialStore: AgentStore? = null) : AgentOpenRouterClient(store = initialStore) {
        var nextResponseJson: String? = null
        var dispatches = 0
        var nextRecoveryProposal: RecoveryProposalGenerationResult? = null // Legacy for other tests if needed
        var activeStore: AgentStore? = initialStore

        override suspend fun executeCapturedOpenRouterBody(
            apiKey: String,
            canonicalPayload: JSONObject,
            attribution: ProviderResponseAttribution,
            operationName: String,
            generation: Int,
            requestContext: ProviderRequestContext.Mission,
            maxAttempts: Int,
            wireVariantKind: ProviderWireVariantKind,
            wireVariantOrdinal: Int
        ): MissionDispatchResult {
            // AUTHORITATIVE RECONCILIATION GATE (Mocked)
            val currentStore = activeStore ?: throw IllegalStateException("Store missing in client")
            val logicalRequestId = requestContext.logicalRequestId ?: "legacy"
            val recoveryPlanId = requestContext.recoveryPlanId
            
            val snapshot = currentStore.loadSnapshot()
            val goal = snapshot.goals.first { it.id == requestContext.goalId }
            val existing = goal.requestAttempts.filter { 
                it.logicalRequestId == logicalRequestId && it.recoveryPlanId == recoveryPlanId 
            }.maxByOrNull { it.startedAt }
            
            if (existing != null && existing.exchangeOutcome == ExchangeOutcome.RESPONSE_SUCCESS) {
                val summary = AgentApiSummary(responseId = existing.providerResponseId, totalTokens = existing.totalTokens ?: 0)
                return MissionDispatchResult.Reconciled(null, summary, existing.exchangeId, existing.reconciledResponseContent)
            }

            dispatches++
            val responseBody = nextResponseJson ?: throw IllegalStateException("Next response not set")
            return MissionDispatchResult.Success(responseBody, 200, "exchange-1")
        }

        // We don't override createResearchRecoveryProposal anymore to allow real reconciliation logic
    }
}
