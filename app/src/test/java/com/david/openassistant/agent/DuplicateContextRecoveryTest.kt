package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.*

class DuplicateContextRecoveryTest {

    @Test
    fun testFingerprintChangesWithRecoveryStrategy() {
        val taskId = "task-1"
        val initialTask = AgentTask(
            id = taskId,
            order = 0,
            title = "Task",
            instructions = "Instructions",
            capability = AgentCapability.REASON
        )
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            tasks = listOf(initialTask),
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model"
        )

        val fp1 = FingerprintUtils.calculateExecutionFingerprint(goal, initialTask)
        
        val taskWithRecovery = initialTask.copy(lastRecoveryStrategy = "Pivot now.")
        val fp2 = FingerprintUtils.calculateExecutionFingerprint(goal, taskWithRecovery)
        
        assertNotEquals("Fingerprint MUST change when recovery strategy is added", fp1, fp2)
    }
}
