package com.david.openassistant

import com.david.openassistant.agent.AgentAttempt
import com.david.openassistant.agent.AgentAttemptStatus
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentEvidence
import com.david.openassistant.agent.AgentEvidenceKind
import com.david.openassistant.agent.AgentExecutionProfile
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentSourceCitation
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS
import com.david.openassistant.agent.MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS
import com.david.openassistant.agent.MAX_RESEARCH_MILESTONE_ATTEMPTS
import com.david.openassistant.agent.hasExhaustedEvidenceBoundedAttemptWindow
import com.david.openassistant.agent.hasExhaustedRequiredToolAttemptWindow
import com.david.openassistant.agent.hasExhaustedResearchAttemptWindow
import com.david.openassistant.agent.AgentExecutionLease
import com.david.openassistant.agent.TaskExecutionTicket
import com.david.openassistant.agent.canCommitMilestoneResult
import com.david.openassistant.agent.localAttemptWindowLimit
import com.david.openassistant.agent.milestoneBoundaryInstruction
import com.david.openassistant.agent.reopenAutomaticResearchWindow
import com.david.openassistant.agent.reopenAutomaticCorrectionWindow
import com.david.openassistant.agent.reopenAutomaticEvidenceBoundedWindow
import com.david.openassistant.agent.selectAgentExecutionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionRecoveryTest {
    @Test
    fun reasoningMilestoneAllowsInteractiveTools() {
        val task = task(AgentCapability.REASON)

        val strategy = selectAgentExecutionStrategy(goal(task), task)

        assertEquals(AgentExecutionProfile.EVIDENCE_BOUNDED_RESPONSE, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
        assertFalse(strategy.reuseCheckpointSources)
    }

    @Test
    fun synthesisMilestoneAllowsNewToolOrSearchLoops() {
        val task = task(AgentCapability.SYNTHESIZE)

        val strategy = selectAgentExecutionStrategy(goal(task), task)

        assertEquals(AgentExecutionProfile.EVIDENCE_BOUNDED_RESPONSE, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
    }

    @Test
    fun repeatedZeroOutputFailuresActivateCompatibilityWhileRetainingTools() {
        val task = task(AgentCapability.DEEP_RESEARCH)
        val goal = goal(task).copy(
            attempts = listOf(
                failure(task.id, "The provider request timed out"),
                failure(task.id, "Provider returned error"),
            ),
        )

        val strategy = selectAgentExecutionStrategy(goal, task)

        assertEquals(AgentExecutionProfile.COMPATIBILITY_RESPONSE, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
    }

    @Test
    fun lateProviderFailuresStillActivateCompatibilityWhenEarlierSubcallsUsedTokens() {
        val task = task(AgentCapability.DEEP_RESEARCH)
        val goal = goal(task).copy(
            attempts = listOf(
                failure(task.id, "The provider request timed out", totalTokens = 5_396),
                failure(task.id, "The provider request timed out"),
            ),
        )

        val strategy = selectAgentExecutionStrategy(goal, task)

        assertEquals(AgentExecutionProfile.COMPATIBILITY_RESPONSE, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
    }

    @Test
    fun usefulResearchCheckpointIsReusedWhileRetainingToolAvailability() {
        val task = task(AgentCapability.DEEP_RESEARCH).copy(attemptCount = 4)
        val goal = goal(task).copy(
            evidence = listOf(
                AgentEvidence(
                    taskId = task.id,
                    kind = AgentEvidenceKind.DEEP_RESEARCH,
                    title = "checkpoint",
                    summary = "research",
                    content = "Analyzed source material. ".repeat(30),
                    sources = listOf(
                        AgentSourceCitation("One", "https://one.example/a"),
                        AgentSourceCitation("Two", "https://two.example/b"),
                    ),
                ),
            ),
        )

        val strategy = selectAgentExecutionStrategy(goal, task)

        assertEquals(AgentExecutionProfile.CHECKPOINT_COMPLETION, strategy.profile)
        assertEquals(true, strategy.reuseCheckpointSources)
        assertTrue(strategy.allowsInteractiveTools)
    }

    @Test
    fun failedCheckpointCompletionReturnsToFreshResearchInsteadOfLoopingForever() {
        val task = task(AgentCapability.DEEP_RESEARCH).copy(attemptCount = 4)
        val goal = goal(task).copy(
            evidence = listOf(
                AgentEvidence(
                    taskId = task.id,
                    kind = AgentEvidenceKind.DEEP_RESEARCH,
                    title = "checkpoint",
                    summary = "research",
                    content = "Analyzed source material. ".repeat(30),
                    sources = listOf(
                        AgentSourceCitation("One", "https://one.example/a"),
                        AgentSourceCitation("Two", "https://two.example/b"),
                    ),
                ),
            ),
            attempts = listOf(
                failure(
                    task.id,
                    "Checkpoint completion did not close the remaining evidence gap.",
                    totalTokens = 9_000,
                ),
            ),
        )

        val strategy = selectAgentExecutionStrategy(goal, task)

        assertEquals(AgentExecutionProfile.FULL, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
    }

    @Test
    fun toolMilestonesKeepToolsEvenAfterProviderFailures() {
        val task = task(AgentCapability.TOOL_USE).copy(attemptCount = 4)
        val goal = goal(task).copy(
            attempts = listOf(failure(task.id, "timeout"), failure(task.id, "timeout")),
        )

        assertEquals(AgentExecutionProfile.FULL, selectAgentExecutionStrategy(goal, task).profile)
    }

    @Test
    fun skippedToolResponseActivatesFocusedRequiredToolRecovery() {
        val message = "The tool-use milestone did not complete a successful local tool call."
        val task = task(AgentCapability.TOOL_USE).copy(attemptCount = 1, lastError = message)
        val goal = goal(task).copy(
            attempts = listOf(failure(task.id, message, totalTokens = 9_000)),
        )

        val strategy = selectAgentExecutionStrategy(goal, task)

        assertEquals(AgentExecutionProfile.FOCUSED_TOOL, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
        assertFalse(strategy.reuseCheckpointSources)
    }

    @Test
    fun requiredToolMilestoneStopsAfterSixZeroToolAttempts() {
        val fifthAttempt = task(AgentCapability.TOOL_USE).copy(
            attemptCount = MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS - 1,
        )
        val sixthAttempt = fifthAttempt.copy(attemptCount = MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS)

        assertFalse(hasExhaustedRequiredToolAttemptWindow(fifthAttempt, toolGatePassed = false))
        assertTrue(hasExhaustedRequiredToolAttemptWindow(sixthAttempt, toolGatePassed = false))
        assertFalse(hasExhaustedRequiredToolAttemptWindow(sixthAttempt, toolGatePassed = true))
        assertFalse(
            hasExhaustedRequiredToolAttemptWindow(
                sixthAttempt.copy(capability = AgentCapability.REASON),
                toolGatePassed = false,
            ),
        )
    }

    @Test
    fun evidenceBoundedMilestoneStopsAfterThreeFailedQualityAttempts() {
        val secondAttempt = task(AgentCapability.REASON).copy(
            attemptCount = MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS - 1,
        )
        val thirdAttempt = secondAttempt.copy(attemptCount = MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS)

        assertFalse(hasExhaustedEvidenceBoundedAttemptWindow(secondAttempt, qualityAccepted = false))
        assertTrue(hasExhaustedEvidenceBoundedAttemptWindow(thirdAttempt, qualityAccepted = false))
        assertFalse(hasExhaustedEvidenceBoundedAttemptWindow(thirdAttempt, qualityAccepted = true))
        assertFalse(
            hasExhaustedEvidenceBoundedAttemptWindow(
                thirdAttempt.copy(capability = AgentCapability.DEEP_RESEARCH),
                qualityAccepted = false,
            ),
        )
    }

    @Test
    fun researchMilestoneRotatesAfterFourFailedQualityAttempts() {
        val thirdAttempt = task(AgentCapability.DEEP_RESEARCH).copy(
            attemptCount = MAX_RESEARCH_MILESTONE_ATTEMPTS - 1,
        )
        val fourthAttempt = thirdAttempt.copy(attemptCount = MAX_RESEARCH_MILESTONE_ATTEMPTS)

        assertFalse(hasExhaustedResearchAttemptWindow(thirdAttempt, qualityAccepted = false))
        assertTrue(hasExhaustedResearchAttemptWindow(fourthAttempt, qualityAccepted = false))
        assertFalse(hasExhaustedResearchAttemptWindow(fourthAttempt, qualityAccepted = true))
        val reopened = fourthAttempt.reopenAutomaticResearchWindow(
            preciseFailure = "The primary-source pass still lacks one verified specification.",
            madeMeaningfulProgress = true,
            now = 42L,
        )
        assertEquals(0, reopened.attemptCount)
        assertEquals(com.david.openassistant.agent.AgentTaskStatus.FAILED, reopened.status)
        assertEquals(
            "The primary-source pass still lacks one verified specification.",
            reopened.lastError,
        )
        assertEquals(42L, reopened.finishedAt)
        assertEquals(
            MAX_RESEARCH_MILESTONE_ATTEMPTS,
            localAttemptWindowLimit(AgentCapability.WEB_RESEARCH),
        )
        assertFalse(
            hasExhaustedResearchAttemptWindow(
                fourthAttempt.copy(capability = AgentCapability.SYNTHESIZE),
                qualityAccepted = false,
            ),
        )
    }

    @Test
    fun correctionMilestoneOpensAFreshAutomaticWindowWithoutDiscardingItsCheckpoint() {
        val exhausted = task(AgentCapability.CORRECT).copy(
            attemptCount = com.david.openassistant.agent.MAX_CORRECTION_MILESTONE_ATTEMPTS,
            progressScore = 0.66,
            outputEvidenceId = "best-correction-checkpoint",
        )

        val reopened = exhausted.reopenAutomaticCorrectionWindow(
            preciseFailure = "The last provider request timed out after partial progress.",
            now = 77L,
        )

        assertEquals(0, reopened.attemptCount)
        assertEquals(com.david.openassistant.agent.AgentTaskStatus.FAILED, reopened.status)
        assertEquals(0.66, reopened.progressScore, 0.0)
        assertEquals("best-correction-checkpoint", reopened.outputEvidenceId)
        assertEquals("The last provider request timed out after partial progress.", reopened.lastError)
        assertEquals(77L, reopened.finishedAt)
    }

    @Test
    fun modelOnlyMilestoneOpensAFreshWindowInsteadOfRequiringManualResume() {
        val exhausted = task(AgentCapability.SYNTHESIZE).copy(
            attemptCount = MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS,
            progressScore = 0.63,
            outputEvidenceId = "synthesis-checkpoint",
        )

        val reopened = exhausted.reopenAutomaticEvidenceBoundedWindow(
            preciseFailure = "One acceptance criterion still needs a supported boundary statement.",
            now = 88L,
        )

        assertEquals(0, reopened.attemptCount)
        assertEquals(com.david.openassistant.agent.AgentTaskStatus.FAILED, reopened.status)
        assertEquals(0.63, reopened.progressScore, 0.0)
        assertEquals("synthesis-checkpoint", reopened.outputEvidenceId)
        assertEquals(88L, reopened.finishedAt)
    }

    @Test
    fun staleOrSupersededAttemptCannotCommitAProviderResult() {
        val runningTask = task(AgentCapability.DEEP_RESEARCH).copy(
            status = com.david.openassistant.agent.AgentTaskStatus.RUNNING,
        )
        val runningAttempt = AgentAttempt(
            taskId = runningTask.id,
            status = AgentAttemptStatus.RUNNING,
            startedAt = 1L,
            modelId = "openrouter/free",
        )
        val activeGoal = goal(runningTask).copy(
            attempts = listOf(runningAttempt),
            executionLease = AgentExecutionLease(
                workerId = "w1",
                ownerProcessSessionId = "test-session",
                taskId = runningTask.id,
                attemptId = "lease-attempt-1",
                generation = 1,
                acquiredAt = 1L,
                heartbeatAt = 1L,
            )
        )
        val ticket = TaskExecutionTicket(
            activeGoal.id,
            runningTask.id,
            "w1",
            "test-session",
            1,
            activeGoal.executionGeneration,
            "lease-attempt-1",
            1L
        )

        assertTrue(canCommitMilestoneResult(activeGoal, runningTask.id, runningAttempt.id, ticket))
        assertFalse(
            canCommitMilestoneResult(
                activeGoal.copy(
                    attempts = listOf(runningAttempt.copy(status = AgentAttemptStatus.FAILED)),
                ),
                runningTask.id,
                runningAttempt.id,
                ticket,
            ),
        )
        assertFalse(
            canCommitMilestoneResult(
                activeGoal.copy(
                    tasks = listOf(runningTask.copy(status = com.david.openassistant.agent.AgentTaskStatus.FAILED)),
                ),
                runningTask.id,
                runningAttempt.id,
                ticket,
            ),
        )
        assertFalse(
            canCommitMilestoneResult(
                activeGoal.copy(status = AgentGoalStatus.CANCELLED),
                runningTask.id,
                runningAttempt.id,
                ticket,
            ),
        )
    }

    @Test
    fun reasonBoundaryAllowsResearchWhenRequiredForDefinitions() {
        val boundary = milestoneBoundaryInstruction(AgentCapability.REASON)

        assertTrue(boundary.contains("Stay focused"))
        assertTrue(boundary.contains("You may search or use tools"))
        assertTrue(boundary.contains("current information are required"))
    }

    @Test
    fun synthesisBoundaryAllowsResearchWhenCriticalGapRemains() {
        val boundary = milestoneBoundaryInstruction(AgentCapability.SYNTHESIZE)

        assertTrue(boundary.contains("Integrate existing evidence"))
        assertTrue(boundary.contains("gather new evidence when a critical gap remains"))
        assertTrue(boundary.contains("complete result"))
    }

    @Test
    fun verificationCorrectionAllowsInteractiveTools() {
        val task = task(AgentCapability.CORRECT)

        val strategy = selectAgentExecutionStrategy(goal(task), task)

        assertEquals(AgentExecutionProfile.COMPATIBILITY_RESPONSE, strategy.profile)
        assertTrue(strategy.allowsInteractiveTools)
        assertFalse(strategy.reuseCheckpointSources)
    }

    private fun task(capability: AgentCapability) = AgentTask(
        id = "task",
        order = 0,
        title = "Research evidence",
        instructions = "Complete the milestone.",
        capability = capability,
    )

    private fun goal(task: AgentTask) = AgentGoal(
        conversationId = "conversation",
        userRequest = "Find supported facts",
        title = "Research",
        objective = "Produce supported work",
        finalOutputDescription = "Verified answer",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "openrouter/free",
        executionModelId = "openrouter/free",
        tasks = listOf(task),
    )

    private fun failure(taskId: String, error: String, totalTokens: Int = 0) = AgentAttempt(
        taskId = taskId,
        status = AgentAttemptStatus.FAILED,
        startedAt = 1L,
        finishedAt = 2L,
        modelId = "openrouter/free",
        totalTokens = totalTokens,
        error = error,
    )
}
