package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class MissionFinalizationTest {

    @Test
    fun transitionToFinalizingClosesRunningAttempts() {
        val now = System.currentTimeMillis()
        val goal = AgentGoal(
            conversationId = "conv-1",
            userRequest = "test",
            title = "test",
            objective = "test",
            finalOutputDescription = "test",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    order = 1,
                    title = "task",
                    instructions = "task",
                    capability = AgentCapability.DEEP_RESEARCH,
                    status = AgentTaskStatus.RUNNING
                )
            ),
            attempts = listOf(
                AgentAttempt(
                    taskId = "task-1",
                    status = AgentAttemptStatus.RUNNING,
                    startedAt = now,
                    modelId = "model"
                )
            )
        )

        val finalized = AgentLifecycleReducer.finalize(goal, now, "Finalizing test")

        assertEquals(AgentGoalStatus.CANCELLING, finalized.status)
        assertEquals(AgentAttemptStatus.FAILED, finalized.attempts[0].status)
        assertEquals("Finalizing test", finalized.attempts[0].error)
        assertTrue(finalized.events.any { it.message == "Finalizing test" })
    }
}
