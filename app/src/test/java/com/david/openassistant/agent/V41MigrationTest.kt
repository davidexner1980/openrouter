package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class V41MigrationTest {

    @Test
    fun identifiesStuckV41Signature() {
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Who is the CEO of OpenAI?",
            title = "Research CEO",
            objective = "Find the CEO of OpenAI",
            finalOutputDescription = "The name of the CEO.",
            status = AgentGoalStatus.PAUSED,
            plannerModelId = "gpt-4o",
            executionModelId = "gpt-4o",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    order = 0,
                    title = "Research",
                    instructions = "Search for CEO",
                    capability = AgentCapability.WEB_RESEARCH,
                    status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
                    failureClass = "STRUCTURED_SYNTHESIS_DEFICIT",
                    attemptCount = 1
                )
            ),
            events = listOf(
                AgentEvent(message = "Execution paused: identical context fingerprint detected. Awaiting new evidence or user intervention.")
            ),
            attempts = listOf(
                AgentAttempt(
                    id = "attempt-1",
                    taskId = "task-1",
                    status = AgentAttemptStatus.RUNNING,
                    startedAt = System.currentTimeMillis(),
                    modelId = "gpt-4o"
                )
            )
        )

        assertTrue(V41Migration.isStuckV41(goal))
    }

    @Test
    fun migratesStuckV41ToBaselineCycle() {
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Who is the CEO of OpenAI?",
            title = "Research CEO",
            objective = "Find the CEO of OpenAI",
            finalOutputDescription = "The name of the CEO.",
            status = AgentGoalStatus.PAUSED,
            plannerModelId = "gpt-4o",
            executionModelId = "gpt-4o",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    order = 0,
                    title = "Research",
                    instructions = "Search for CEO",
                    capability = AgentCapability.WEB_RESEARCH,
                    status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
                    failureClass = "STRUCTURED_SYNTHESIS_DEFICIT",
                    attemptCount = 1
                )
            ),
            events = listOf(
                AgentEvent(message = "Execution paused: identical context fingerprint detected. Awaiting new evidence or user intervention.")
            ),
            attempts = listOf(
                AgentAttempt(
                    id = "attempt-1",
                    taskId = "task-1",
                    status = AgentAttemptStatus.RUNNING,
                    startedAt = System.currentTimeMillis(),
                    modelId = "gpt-4o"
                )
            )
        )

        val migrated = V41Migration.migrate(goal)

        assertEquals(1, migrated.researchCycles.size)
        assertEquals(1, migrated.objectiveRevisions.size)
        assertEquals(migrated.researchCycles[0].id, migrated.activeResearchCycleId)
        assertEquals(AgentTaskStatus.QUEUED, migrated.tasks[0].status)
        assertNull(migrated.tasks[0].failureClass)
        assertEquals(migrated.activeResearchCycleId, migrated.tasks[0].cycleId)
        assertEquals(AgentAttemptStatus.FAILED, migrated.attempts[0].status)
        assertTrue(migrated.events.last().message.contains("Migrated legacy V41 mission"))
    }

    @Test
    fun doesNotMigrateNormalUserPause() {
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Test",
            title = "Test",
            objective = "Test",
            finalOutputDescription = "Test",
            status = AgentGoalStatus.PAUSED,
            plannerModelId = "gpt-4o",
            executionModelId = "gpt-4o",
            tasks = emptyList(),
            events = listOf(
                AgentEvent(message = "Goal paused by the user.")
            )
        )

        assertFalse(V41Migration.isStuckV41(goal))
    }
}
