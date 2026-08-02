package com.david.openassistant.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AgentRaceConditionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private val goalId = "goal-1"

    @Before
    fun setup() {
        val root = tempFolder.newFolder()
        store = AgentStore(root)
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "test request",
            title = "Title",
            objective = "objective",
            finalOutputDescription = "output",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = listOf(
                AgentTask(id = "task-1", order = 0, title = "Task 1", instructions = "do it", capability = AgentCapability.REASON)
            )
        )
        store.upsertGoal(goal)
    }

    @Test
    fun competingReclamations_ExactlyOneWorkerSucceeds() {
        // 1. Create an orphan lease
        store.updateGoalAtomic(goalId, null) { current ->
            current.copy(
                executionLease = AgentExecutionLease(
                    workerId = "dead-worker",
                    ownerProcessSessionId = "dead-session",
                    taskId = "task-1",
                    attemptId = "dead-attempt",
                    generation = 1,
                    acquiredAt = System.currentTimeMillis(),
                    heartbeatAt = System.currentTimeMillis()
                ),
                leaseGeneration = 1
            )
        }

        runBlocking {
            val results = (1..50).map { i ->
                async {
                    store.acquireTaskLeaseAtomic(goalId, "worker-$i", "task-1")
                }
            }.awaitAll()

            val successes = results.count { it is LeaseAcquisitionResult.OrphanReclaimed || it is LeaseAcquisitionResult.Acquired }
            val contentions = results.count { it is LeaseAcquisitionResult.LiveOwnerPresent }

            assertEquals("Exactly one worker must reclaim the orphan", 1, successes)
            assertEquals("All other workers must observe a live owner", 49, contentions)
            
            val finalGoal = store.loadSnapshot().goals.first()
            assertEquals(2, finalGoal.leaseGeneration)
        }
    }
}
