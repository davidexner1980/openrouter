package com.david.openassistant.agent

import com.david.openassistant.domain.AgentInteractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*

class RestartSchedulingFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var interactor: AgentInteractor
    private lateinit var scheduler: MockAgentScheduler
    private val goalId = "goal-1"

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)
        scheduler = MockAgentScheduler()
        interactor = AgentInteractor(
            context = null,
            agentStore = store,
            agentScheduler = scheduler,
            boundaryHook = null
        )
    }

    @Test
    fun testRestartFailureBecomesDurableBlocked() = runBlocking {
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Req",
            title = "Title",
            objective = "Obj",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.CANCELLED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList(),
            executionGeneration = 1
        )
        store.upsertGoal(goal, true)

        // Configure mock scheduler to fail
        val expectedError = IllegalStateException("WorkManager Enqueue Failed")
        scheduler.nextResult = SchedulingResult.EnqueueFailed(expectedError)

        val result = interactor.restartAgentGoal(goalId)
        
        assertTrue(result is SchedulingResult.EnqueueFailed)
        
        val reloaded = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, reloaded.status)
        assertNotNull(reloaded.error)
        assertTrue(reloaded.error!!.contains("Restart scheduling failed"))
        assertEquals(2, reloaded.executionGeneration)
    }

    private class MockAgentScheduler : IAgentScheduler {
        var nextResult: SchedulingResult = SchedulingResult.NewlyEnqueued(UUID.randomUUID(), androidx.work.WorkInfo.State.ENQUEUED)

        override fun enqueue(goalId: String, replace: Boolean, executionGeneration: Int) {}
        override fun enqueueAndWait(goalId: String, replace: Boolean, executionGeneration: Int, activeLease: AgentExecutionLease?): SchedulingResult = nextResult
        override fun enqueueContinuation(goalId: String, executionGeneration: Int, claimantLeaseGeneration: Int, fingerprint: String?): SchedulingResult = nextResult
        override fun cancel(goalId: String, executionGeneration: Int) {}
        override fun cancelAndWait(goalId: String, executionGeneration: Int) {}
        override fun cancelAllForGoal(goalId: String) {}
        override fun isWorkRunning(goalId: String, executionGeneration: Int): Boolean = false
        override fun schedulePeriodicRecovery() {}
    }
}
