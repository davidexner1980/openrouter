package com.david.openassistant

import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentLifecycleReducer
import com.david.openassistant.agent.AgentStateMachine
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.AgentTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeTest {
    @Test
    fun stateMachineAllowsDurableExecutionPath() {
        assertTrue(AgentStateMachine.canTransition(AgentGoalStatus.QUEUED, AgentGoalStatus.RUNNING))
        assertTrue(AgentStateMachine.canTransition(AgentGoalStatus.RUNNING, AgentGoalStatus.VERIFYING))
        assertTrue(AgentStateMachine.canTransition(AgentGoalStatus.VERIFYING, AgentGoalStatus.COMPLETED))
    }

    @Test
    fun terminalStatesCannotRestartSilently() {
        assertFalse(AgentStateMachine.canTransition(AgentGoalStatus.COMPLETED, AgentGoalStatus.RUNNING))
        // Now allowed for explicit restart
        assertTrue(AgentStateMachine.canTransition(AgentGoalStatus.CANCELLED, AgentGoalStatus.QUEUED))
    }

    @Test
    fun nextRunnableTaskWaitsForDependencies() {
        val first = AgentTask(
            id = "first",
            order = 0,
            title = "First",
            instructions = "Do first",
            capability = AgentCapability.REASON,
            status = AgentTaskStatus.COMPLETED,
        )
        val second = AgentTask(
            id = "second",
            order = 1,
            title = "Second",
            instructions = "Do second",
            capability = AgentCapability.SYNTHESIZE,
            dependsOn = listOf("first"),
            status = AgentTaskStatus.QUEUED,
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "Complete a goal",
            title = "Goal",
            objective = "Complete it",
            finalOutputDescription = "Result",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "planner",
            executionModelId = "executor",
            tasks = listOf(first, second),
        )

        assertEquals("second", goal.nextRunnableTask?.id)
    }
    @Test
    fun failedMilestoneRemainsRunnableAfterManyAttempts() {
        val failed = AgentTask(
            id = "persistent",
            order = 0,
            title = "Keep working",
            instructions = "Finish the job",
            capability = AgentCapability.DEEP_RESEARCH,
            status = AgentTaskStatus.FAILED,
            attemptCount = 10_000,
            lastError = "Previous attempt was incomplete.",
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "Complete a goal",
            title = "Goal",
            objective = "Complete it",
            finalOutputDescription = "Result",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "planner",
            executionModelId = "executor",
            tasks = listOf(failed),
        )

        assertEquals("persistent", goal.nextRunnableTask?.id)
    }

    @Test
    fun resumingABoundedResearchFailureStartsAFreshAttemptWindow() {
        val failed = AgentTask(
            id = "persistent",
            order = 0,
            title = "Keep working",
            instructions = "Finish the job",
            capability = AgentCapability.DEEP_RESEARCH,
            status = AgentTaskStatus.FAILED,
            attemptCount = 10_000,
            lastError = "Credential was unavailable.",
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "Complete a goal",
            title = "Goal",
            objective = "Complete it",
            finalOutputDescription = "Result",
            status = AgentGoalStatus.FAILED,
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            tasks = listOf(failed),
            error = "Credential was unavailable.",
        )

        val resumed = AgentLifecycleReducer.resume(goal)

        assertEquals(AgentGoalStatus.QUEUED, resumed.status)
        assertEquals(0, resumed.tasks.single().attemptCount)
        assertEquals(AgentTaskStatus.QUEUED, resumed.tasks.single().status)
    }

    @Test
    fun resumingABoundedReasoningFailureStartsAFreshAttemptWindow() {
        val failed = AgentTask(
            id = "reason",
            order = 0,
            title = "Define the decision framework",
            instructions = "Record criteria and evidence needs.",
            capability = AgentCapability.REASON,
            status = AgentTaskStatus.FAILED,
            attemptCount = 3,
            lastError = "Reason milestone reached its local quality window.",
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "Compare products",
            title = "Comparison",
            objective = "Finish the comparison",
            finalOutputDescription = "Verified result",
            status = AgentGoalStatus.FAILED,
            plannerModelId = "openrouter/auto",
            executionModelId = "openrouter/auto",
            tasks = listOf(failed),
            error = failed.lastError,
        )

        val resumed = AgentLifecycleReducer.resume(goal)

        assertEquals(AgentGoalStatus.QUEUED, resumed.status)
        assertEquals(AgentTaskStatus.QUEUED, resumed.tasks.single().status)
        assertEquals(0, resumed.tasks.single().attemptCount)
    }

    @Test
    fun resumingABoundedToolFailureStartsAFreshToolAttemptWindow() {
        val failed = AgentTask(
            id = "tool",
            order = 0,
            title = "Calculate value",
            instructions = "Calculate the value ratio.",
            capability = AgentCapability.TOOL_USE,
            status = AgentTaskStatus.FAILED,
            attemptCount = 6,
            lastError = "Tool-use milestone did not complete a successful local tool call after 6 bounded attempts.",
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "Compare products",
            title = "Comparison",
            objective = "Finish the comparison",
            finalOutputDescription = "Verified result",
            status = AgentGoalStatus.FAILED,
            plannerModelId = "openrouter/auto",
            executionModelId = "openrouter/auto",
            tasks = listOf(failed),
            error = failed.lastError,
        )

        val resumed = AgentLifecycleReducer.resume(goal)

        assertEquals(AgentGoalStatus.QUEUED, resumed.status)
        assertEquals(AgentTaskStatus.QUEUED, resumed.tasks.single().status)
        assertEquals(0, resumed.tasks.single().attemptCount)
    }

    @Test
    fun credentialWaitCanResumeWithoutLosingCompletedWork() {
        val task = AgentTask(
            id = "done",
            order = 0,
            title = "Done",
            instructions = "Done",
            capability = AgentCapability.REASON,
            status = AgentTaskStatus.COMPLETED,
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "Complete a goal",
            title = "Goal",
            objective = "Complete it",
            finalOutputDescription = "Result",
            status = AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            tasks = listOf(task),
        )

        val resumed = AgentLifecycleReducer.resume(goal)

        assertEquals(AgentGoalStatus.QUEUED, resumed.status)
        assertEquals(AgentTaskStatus.COMPLETED, resumed.tasks.single().status)
    }

}
