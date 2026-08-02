package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContinuationRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private val goalId = "runnable-goal"

    @Before
    fun setup() {
        val root = tempFolder.newFolder()
        store = AgentStore(root)
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "test",
            title = "Title",
            objective = "objective",
            finalOutputDescription = "output",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "m1",
            executionModelId = "m1",
            tasks = listOf(
                AgentTask(id = "task-1", order = 0, title = "Task 1", instructions = "do it", capability = AgentCapability.REASON)
            ),
            executionLease = null
        )
        store.upsertGoal(goal)
    }

    @Test
    fun shouldRecoverLogic_CorrectlyIdentifiesRunnableGoalWithNoLease() {
        val snapshot = store.loadSnapshot()
        val goal = snapshot.goals.first()
        
        val lease = goal.executionLease
        val now = System.currentTimeMillis()
        
        val shouldRecover = when {
            goal.status == AgentGoalStatus.WAITING_FOR_NETWORK -> {
                goal.nextRetryAt != null && now >= goal.nextRetryAt
            }
            lease == null -> true
            AgentLeasePolicy.isStale(lease, now) -> true
            else -> false
        }
        
        assertTrue("Goal in QUEUED status with no lease must be recovered", shouldRecover)
    }
}
