package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.OkHttpClient

class DuplicateContextFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var client: AgentOpenRouterClient
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var executor: AgentTaskExecutor

    private val goalId = "goal-1"
    private val taskId = "task-1"

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val diagDir = tempFolder.newFolder("diagnostics")
        diagnostics = RuntimeDiagnostics(null, diagDir, null)

        client = AgentOpenRouterClient(
            client = OkHttpClient(),
            store = store,
            diagnostics = diagnostics
        )

        executor = AgentTaskExecutor(
            client = client,
            store = store,
            diagnostics = diagnostics,
            autonomyPolicy = AutonomyPolicy(),
            beforeCommitHook = null
        )
    }

    @Test
    fun testPolicyInstantiation() {
        val p = AutonomyPolicy()
        assertNotNull(p)
    }

    @Test
    fun testExhaustionFallbackToResearchCyclesExhausted() = runBlocking {
        val task = AgentTask(
            id = taskId,
            order = 0,
            title = "Task",
            instructions = "Instructions",
            capability = AgentCapability.REASON,
            attemptCount = 1
        )

        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(task),
            recoveryPlans = listOf(
                createCommittedPlan("plan-1", EscalationTactic.REBUILD_QUERY_PORTFOLIO),
                createCommittedPlan("plan-2", EscalationTactic.FOLLOW_RELEVANT_LINKS),
                createCommittedPlan("plan-3", EscalationTactic.SHIFT_SOURCE_FAMILY),
                createCommittedPlan("plan-4", EscalationTactic.CYCLE_ADVANCE),
                createCommittedPlan("plan-5", EscalationTactic.ASK_USER)
            )
        )

        val currentFingerprint = FingerprintUtils.calculateExecutionFingerprint(goal, task)
        val taskWithFingerprint = task.copy(lastRequestFingerprint = currentFingerprint)
        val goalWithTask = goal.copy(tasks = listOf(taskWithFingerprint))

        store.upsertGoal(goalWithTask, true)

        val acquisition = store.acquireTaskLeaseAtomic(goalId, "worker-1", taskId)
        assertTrue("Acquisition failed", acquisition is LeaseAcquisitionResult.Acquired)
        val validTicket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        val outcome = executor.executeOneTask("api-key", acquisition.goal, taskWithFingerprint, validTicket, emptyList())
        assertEquals(WorkerOutcome.CONTINUE, outcome)

        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED, finalGoal.status)
    }

    private fun createCommittedPlan(id: String, tactic: EscalationTactic) = ResearchRecoveryPlan(
        id = id,
        goalId = goalId,
        taskId = taskId,
        inputExecutionFingerprint = "any",
        diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
        selectedTactic = tactic,
        status = RecoveryPlanStatus.COMMITTED,
        logicalProviderRequestId = null,
        proposal = null,
        proposalFingerprint = null,
        validationResult = null,
        failureClassification = null,
        failureMessage = null
    )
}
