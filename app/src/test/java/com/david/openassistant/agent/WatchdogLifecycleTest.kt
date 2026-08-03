package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class WatchdogLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)
    }

    private fun createTestGoal(
        id: String = UUID.randomUUID().toString(),
        status: AgentGoalStatus = AgentGoalStatus.QUEUED,
        tasks: List<AgentTask> = emptyList(),
        lease: AgentExecutionLease? = null
    ): AgentGoal {
        return AgentGoal(
            id = id,
            conversationId = "c1",
            userRequest = "Request",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Output",
            status = status,
            plannerModelId = "m1",
            executionModelId = "m2",
            tasks = tasks,
            executionLease = lease
        )
    }

    @Test
    fun testWatchdogSkipsPausedGoal() {
        val goal = createTestGoal(status = AgentGoalStatus.PAUSED)
        store.saveSnapshot(AgentSnapshot(goals = listOf(goal)))

        val snapshot = store.loadSnapshot()
        val loadedGoal = snapshot.goals.first()
        val now = System.currentTimeMillis()
        val isRecoverable = isRecoverableInWorker(loadedGoal, now)
        
        assertFalse("Watchdog should skip PAUSED goal", isRecoverable)
    }

    @Test
    fun testWatchdogRecoversStrandedResearchPhase() {
        val goal = createTestGoal(status = AgentGoalStatus.RESEARCHING, lease = null)
        store.saveSnapshot(AgentSnapshot(goals = listOf(goal)))

        val snapshot = store.loadSnapshot()
        val loadedGoal = snapshot.goals.first()
        val now = System.currentTimeMillis()
        val isRecoverable = isRecoverableInWorker(loadedGoal, now)
        
        assertTrue("Watchdog should NOW recover RESEARCHING status", isRecoverable)
    }

    @Test
    fun testWatchdogRecoversRunningGoalWithStaleLease() {
        val staleLease = AgentExecutionLease(
            workerId = "w1",
            ownerProcessSessionId = "s1",
            taskId = "t1",
            attemptId = "a1",
            acquiredAt = System.currentTimeMillis() - 400_000, // 6.6 minutes ago
            heartbeatAt = System.currentTimeMillis() - 400_000,
            generation = 1
        )
        val goal = createTestGoal(status = AgentGoalStatus.RUNNING, lease = staleLease)
        store.saveSnapshot(AgentSnapshot(goals = listOf(goal)))

        val snapshot = store.loadSnapshot()
        val loadedGoal = snapshot.goals.first()
        val now = System.currentTimeMillis()
        val isRecoverable = isRecoverableInWorker(loadedGoal, now)
        
        assertTrue("Watchdog should recover RUNNING goal with stale lease", isRecoverable)
    }

    @Test
    fun testWatchdogSkipsPausedGoalWithStaleLease() {
        val staleLease = AgentExecutionLease(
            workerId = "w1",
            ownerProcessSessionId = "s1",
            taskId = "t1",
            attemptId = "a1",
            acquiredAt = System.currentTimeMillis() - 400_000,
            heartbeatAt = System.currentTimeMillis() - 400_000,
            generation = 1
        )
        val goal = createTestGoal(status = AgentGoalStatus.PAUSED, lease = staleLease)
        store.saveSnapshot(AgentSnapshot(goals = listOf(goal)))

        val snapshot = store.loadSnapshot()
        val loadedGoal = snapshot.goals.first()
        val now = System.currentTimeMillis()
        val isRecoverable = isRecoverableInWorker(loadedGoal, now)
        
        assertFalse("Watchdog should skip PAUSED goal even with stale lease", isRecoverable)
    }

    /**
     * Mimics UPDATED logic from MissionRecoveryWorker.kt
     */
    private fun isRecoverableInWorker(goal: AgentGoal, now: Long): Boolean {
        val lease = goal.executionLease
        val isStale = AgentLeasePolicy.isStale(lease, now)
        
        val isRecoverableStatus = when {
            goal.status.isActivePhase() -> true
            goal.status == AgentGoalStatus.WAITING_FOR_NETWORK -> goal.nextRetryAt != null && now >= goal.nextRetryAt
            else -> false
        }
        
        return isRecoverableStatus && (lease == null || isStale)
    }
}
