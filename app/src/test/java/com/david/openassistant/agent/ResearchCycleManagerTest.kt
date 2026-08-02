package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import java.util.UUID

class ResearchCycleManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var client: AgentOpenRouterClient
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var manager: ResearchCycleManager

    @Before
    fun setUp() {
        val root = tempFolder.newFolder()
        store = AgentStore(root)
        diagnostics = RuntimeDiagnostics(null, root, null)
        client = AgentOpenRouterClient(null, AutonomyPolicy.DEFAULT, okhttp3.OkHttpClient(), null, diagnostics, store)
        manager = ResearchCycleManager(store, client, diagnostics)
    }

    @Test
    fun prepareRecoveryIsIdempotent() = runBlocking {
        val goalId = "goal-1"
        val taskId = "task-1"
        val inputFp = "fp1"
        val cycleId = "cycle-1"
        val workerId = "worker-1"
        
        val initialGoal = AgentGoal(
            id = goalId,
            conversationId = "c1",
            userRequest = "T", title = "T", objective = "O", finalOutputDescription = "D",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "m1", executionModelId = "m2",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "T", instructions = "I", capability = AgentCapability.WEB_RESEARCH))
        )
        store.upsertGoal(initialGoal)
        
        // 1. Acquire lease to get a valid ticket and set cycleId correctly via migration during decode
        val acquisition = store.acquireTaskLeaseAtomic(goalId, workerId, taskId)
        assertTrue(acquisition is LeaseAcquisitionResult.Acquired)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket
        val goal = acquisition.goal
        val task = goal.tasks[0]

        val decision = ResearchRecoveryEngine.RecoveryDecision(
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            tactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            kind = RecoveryKind.TACTIC_PIVOT,
            explanation = "Test"
        )
        
        // First call should create a plan
        val plan1 = manager.prepareRecovery(goal, task, decision, inputFp, ticket as TaskExecutionTicket)
        assertNotNull(plan1)
        
        val snapshot = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(1, snapshot.recoveryPlans.size)
        assertEquals(plan1.id, snapshot.activeRecoveryPlanId)

        // Second call with same inputs should return same plan
        val plan2 = manager.prepareRecovery(snapshot, snapshot.tasks[0], decision, inputFp, ticket)
        assertEquals(plan1.id, plan2.id)
        
        val snapshot2 = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(1, snapshot2.recoveryPlans.size)
    }
}
