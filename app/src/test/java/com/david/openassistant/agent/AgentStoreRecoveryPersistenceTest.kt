package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentStoreRecoveryPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = tempFolder.newFolder("agent_store_recovery")
        store = AgentStore(tempDir)
    }

    @Test
    fun `test recovery plan persistence round trip`() {
        val taskId = "task-123"
        val proposal = RecoveryProposal(
            revisedInvestigationInterpretation = "Interpretation",
            specificUnresolvedGap = "Gap",
            selectedSourceFamilyShift = "News",
            evidenceTargets = listOf("Target1"),
            falsifiers = listOf("Falsifier1"),
            newQueryPortfolio = listOf("Query1", "Query2"),
            followUpRule = "Rule",
            rationale = "Rationale",
            expectedNoveltyDimensions = listOf("Dimension1")
        )
        val plan = ResearchRecoveryPlan(
            id = "plan-sha-256",
            goalId = "goal-1",
            taskId = taskId,
            inputExecutionFingerprint = "fingerprint-1",
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.SHIFT_SOURCE_FAMILY,
            status = RecoveryPlanStatus.READY_TO_COMMIT,
            logicalProviderRequestId = "req-1",
            proposal = proposal,
            proposalFingerprint = "prop-fingerprint",
            validationResult = "Valid",
            failureClassification = null,
            failureMessage = null,
            generatedAt = System.currentTimeMillis()
        )

        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Request",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Description",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "planner",
            executionModelId = "executor",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = plan.id
        )

        store.upsertGoal(goal)
        val loaded = store.loadSnapshot().goals.first { it.id == "goal-1" }

        assertEquals(1, loaded.recoveryPlans.size)
        val loadedPlan = loaded.recoveryPlans[0]
        assertEquals(plan.id, loadedPlan.id)
        assertEquals(plan.diagnosis, loadedPlan.diagnosis)
        assertEquals(plan.selectedTactic, loadedPlan.selectedTactic)
        assertEquals(plan.status, loadedPlan.status)
        assertNotNull(loadedPlan.proposal)
        assertEquals(proposal.revisedInvestigationInterpretation, loadedPlan.proposal?.revisedInvestigationInterpretation)
        assertEquals(proposal.newQueryPortfolio, loadedPlan.proposal?.newQueryPortfolio)
        assertEquals(goal.activeRecoveryPlanId, loaded.activeRecoveryPlanId)
    }
}
