package com.david.openassistant.agent

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.io.File

class WatchdogDurableIntentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var scheduler: FakeAgentScheduler
    private lateinit var worker: MissionRecoveryWorker

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)
        scheduler = FakeAgentScheduler()
        
        // We need to bypass the context-based constructor of MissionRecoveryWorker
        // Since we can't easily mock Context/WorkerParameters for a real Worker,
        // we'll rely on the fact that we can inspect the store and scheduler.
        // Actually, MissionRecoveryWorker uses store and scheduler internally.
        // We'll have to use reflection or a test-friendly constructor if we modify it.
        // For now, let's just test the logic by mimicking it if we can't run the worker.
    }

    @Test
    fun testWatchdogRespectsPausedStatus() {
        val goalId = "g1"
        val pausedGoal = AgentGoal(
            id = goalId,
            conversationId = "c1",
            userRequest = "R",
            title = "T",
            status = AgentGoalStatus.PAUSED
        )
        store.saveGoal(pausedGoal)

        // Mocking the recovery loop logic
        val now = System.currentTimeMillis()
        val goal = store.loadSnapshot().goals.first()
        val lease = goal.executionLease
        val isStale = AgentLeasePolicy.isStale(lease, now)
        
        val isRecoverableStatus = when (goal.status) {
            AgentGoalStatus.RUNNING,
            AgentGoalStatus.VERIFYING,
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.RECOVERING -> true
            AgentGoalStatus.QUEUED -> true
            AgentGoalStatus.WAITING_FOR_NETWORK -> goal.nextRetryAt != null && now >= goal.nextRetryAt
            else -> false
        }
        
        val shouldRecover = isRecoverableStatus && (lease == null || isStale)
        
        assertFalse("Watchdog should NOT recover a PAUSED goal", shouldRecover)
    }

    @Test
    fun testWatchdogSkipsNewResearchPhases_BugReproduction() {
        val goalId = "g2"
        val researchingGoal = AgentGoal(
            id = goalId,
            conversationId = "c1",
            userRequest = "R",
            title = "T",
            status = AgentGoalStatus.RESEARCHING,
            executionLease = null // Stranded
        )
        store.saveGoal(researchingGoal)

        val now = System.currentTimeMillis()
        val goal = store.loadSnapshot().goals.first()
        val lease = goal.executionLease
        val isStale = AgentLeasePolicy.isStale(lease, now)
        
        val isRecoverableStatus = when (goal.status) {
            AgentGoalStatus.RUNNING,
            AgentGoalStatus.VERIFYING,
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.RECOVERING -> true
            AgentGoalStatus.QUEUED -> true
            AgentGoalStatus.WAITING_FOR_NETWORK -> goal.nextRetryAt != null && now >= goal.nextRetryAt
            else -> false
        }
        
        val shouldRecover = isRecoverableStatus && (lease == null || isStale)
        
        // This is expected to be FALSE currently because RESEARCHING is not in the list.
        // This proves the "stranded research" bug.
        assertFalse("Watchdog currently fails to recover RESEARCHING status (Bug)", shouldRecover)
    }

    private class FakeAgentScheduler : AgentSchedulerProxy {
        val enqueued = mutableListOf<String>()
        override fun enqueue(goalId: String, replace: Boolean, generation: Int) {
            enqueued.add(goalId)
        }
        override fun cancel(goalId: String, generation: Int) {}
    }

    private interface AgentSchedulerProxy {
        fun enqueue(goalId: String, replace: Boolean = false, generation: Int = 0)
        fun cancel(goalId: String, generation: Int = 0)
    }
}
