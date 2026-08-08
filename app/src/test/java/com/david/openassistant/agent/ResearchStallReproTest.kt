package com.david.openassistant.agent

import com.david.openassistant.agent.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

class ResearchStallReproTest {

    @Test
    fun testStall_whenProgressIs100ButQualityFails_reopensWindow() = runTest {
        // Mock dependencies
        val store = mockk<AgentStore>(relaxed = true)
        val client = mockk<AgentOpenRouterClient>(relaxed = true)
        val diagnostics = mockk<com.david.openassistant.data.diagnostics.RuntimeDiagnostics>(relaxed = true)
        val policy = AutonomyPolicy.DEFAULT
        
        val task = AgentTask(
            id = "task-1",
            order = 0,
            title = "Test Task",
            instructions = "Test",
            capability = AgentCapability.CORRECT,
            status = AgentTaskStatus.RUNNING,
            attemptCount = MAX_CORRECTION_MILESTONE_ATTEMPTS, // Hits window limit
            progressScore = 1.0 // 100% progress
        )
        
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Test request",
            title = "Test Goal",
            objective = "Test objective",
            finalOutputDescription = "Test",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = listOf(task)
        )
        
        val executor = AgentTaskExecutor(client, store, diagnostics, policy)
        
        // This is a unit test for logic, not a full integration test.
        // We'll verify that reopenAutomaticCorrectionWindow is called or its effect is achieved.
        
        val updatedTask = task.reopenAutomaticCorrectionWindow("Failure", System.currentTimeMillis())
        
        assertEquals(AgentTaskStatus.FAILED, updatedTask.status)
        assertEquals(0, updatedTask.attemptCount)
        assertEquals(1, updatedTask.automaticWindowReopenCount)
        assertEquals(1, updatedTask.globalAutomaticWindowReopenCount)
    }

    @Test
    fun testStall_whenReopenLimitExhausted_terminatesAsBlocked() = runTest {
        val task = AgentTask(
            id = "task-1",
            order = 0,
            title = "Test Task",
            instructions = "Test",
            capability = AgentCapability.CORRECT,
            status = AgentTaskStatus.RUNNING,
            attemptCount = MAX_CORRECTION_MILESTONE_ATTEMPTS,
            globalAutomaticWindowReopenCount = MAX_GLOBAL_AUTOMATIC_CORRECTION_REOPENS
        )
        
        val updatedTask = task.reopenAutomaticCorrectionWindow("Failure", System.currentTimeMillis())
        
        assertEquals(AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE, updatedTask.status)
        assertEquals("Exhausted 3 automatic correction reopen windows. Preserved evidence and partial work remain available. Last deficiency: Failure", updatedTask.lastError)
    }
}
