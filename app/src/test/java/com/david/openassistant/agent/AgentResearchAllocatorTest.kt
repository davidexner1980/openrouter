package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResearchAllocatorTest {

    @Test
    fun currentFinancialRecommendationReceivesHighRiskFreshAllocation() {
        val goal = testGoal(
            request = "Compare the best current investment platforms for 2026 and recommend the safest option.",
        )

        val profile = AgentResearchAllocator.profileForGoal(goal)

        assertEquals(ResearchRisk.HIGH, profile.risk)
        assertEquals(FreshnessNeed.REQUIRED, profile.freshnessNeed)
        assertEquals(ContradictionNeed.HIGH, profile.contradictionNeed)
        assertEquals(ModelStrength.STRONG, profile.synthesisModelStrength)
        assertTrue(profile.targetDistinctSources >= 10)
    }

    @Test
    fun financialsAndPotentialReceivesHighStakesAllocation() {
        val goal = testGoal(
            request = "Go through the financials, revenue, debt, valuation, and future potential of this company in great detail.",
        )

        val profile = AgentResearchAllocator.profileForGoal(goal)

        assertEquals(ResearchRisk.MEDIUM, profile.risk)
        assertTrue(profile.targetDistinctSources >= 10)
        assertTrue(profile.targetDomains >= 4)
        assertTrue(profile.explanation.contains("HIGH complexity"))
    }

    @Test
    fun allocatorComputesSourceAndDomainGapsFromPreservedEvidence() {
        val goal = testGoal(
            request = "Research current official specifications for a safety standard.",
            evidence = listOf(
                evidence("https://example.gov/a", "https://standards.example.org/spec"),
                evidence("https://example.gov/b"),
            ),
        )
        val profile = AgentResearchAllocator.profileForGoal(goal)

        val gaps = AgentResearchAllocator.evaluateGaps(goal, profile)

        assertTrue(gaps.remainingSourceGap > 0)
        assertTrue(gaps.remainingDomainGap >= 0)
        assertTrue(gaps.remainingPrimarySourceGap)
    }

    @Test
    fun selectorChoosesReadyTaskInsteadOfStoppingOnCooldownPriorityTask() {
        val now = System.currentTimeMillis()
        val primary = task(
            id = "research_primary",
            title = "Primary verification",
            order = 0,
            cooldownUntil = now + 60_000,
        )
        val contradiction = task(
            id = "research_contradictions",
            title = "Adversarial review",
            order = 1,
        )
        val goal = testGoal(
            request = "Compare the best current options and verify official sources.",
            tasks = listOf(primary, contradiction),
        )
        val profile = AgentResearchAllocator.profileForGoal(goal)

        val selection = AgentResearchAllocator.chooseNextTask(goal, profile, now)

        assertEquals("research_contradictions", selection.taskId)
        assertFalse(selection.retryAfterCooldown)
    }

    @Test
    fun selectorRetriesWhenEveryDependencySatisfiedTaskIsCoolingDown() {
        val now = System.currentTimeMillis()
        val goal = testGoal(
            tasks = listOf(
                task(
                    id = "research_primary",
                    title = "Primary verification",
                    cooldownUntil = now + 60_000,
                ),
            ),
        )
        val profile = AgentResearchAllocator.profileForGoal(goal)

        val selection = AgentResearchAllocator.chooseNextTask(goal, profile, now)

        assertNull(selection.taskId)
        assertTrue(selection.retryAfterCooldown)
    }

    @Test
    fun selectorNeverChoosesDependencyBlockedTask() {
        val blocked = task(
            id = "research_primary",
            title = "Primary verification",
            dependsOn = listOf("discovery"),
        )
        val ready = task(id = "interpret", title = "Interpret request", capability = AgentCapability.REASON)
        val goal = testGoal(tasks = listOf(blocked, ready))
        val profile = AgentResearchAllocator.profileForGoal(goal)

        val selection = AgentResearchAllocator.chooseNextTask(goal, profile)

        assertEquals("interpret", selection.taskId)
    }

    @Test
    fun resumeReducerRequeuesStrandedQueuedGoal() {
        val goal = testGoal(
            status = AgentGoalStatus.QUEUED,
            tasks = listOf(task(status = AgentTaskStatus.RUNNING, attemptCount = 1)),
        )

        val resumed = AgentLifecycleReducer.resume(goal)

        assertEquals(AgentGoalStatus.QUEUED, resumed.status)
        assertNotNull(resumed.events.lastOrNull())
        assertEquals(AgentTaskStatus.QUEUED, resumed.tasks.single().status)
        assertEquals(0, resumed.tasks.single().attemptCount)
    }

    private fun testGoal(
        request: String = "Research the best current official source.",
        status: AgentGoalStatus = AgentGoalStatus.QUEUED,
        tasks: List<AgentTask> = emptyList(),
        evidence: List<AgentEvidence> = emptyList(),
    ) = AgentGoal(
        id = "goal-1",
        conversationId = "conv-1",
        userRequest = request,
        title = "Research goal",
        objective = "Research objective",
        finalOutputDescription = "Research result",
        status = status,
        plannerModelId = "openrouter/auto-beta",
        executionModelId = "openrouter/auto-beta",
        tasks = tasks,
        evidence = evidence,
    )

    private fun task(
        id: String = "task-1",
        title: String = "Task",
        order: Int = 0,
        capability: AgentCapability = AgentCapability.DEEP_RESEARCH,
        status: AgentTaskStatus = AgentTaskStatus.QUEUED,
        dependsOn: List<String> = emptyList(),
        attemptCount: Int = 0,
        cooldownUntil: Long? = null,
    ) = AgentTask(
        id = id,
        order = order,
        title = title,
        instructions = "Do the work.",
        capability = capability,
        status = status,
        dependsOn = dependsOn,
        attemptCount = attemptCount,
        cooldownUntil = cooldownUntil,
    )

    private fun evidence(vararg urls: String) = AgentEvidence(
        kind = AgentEvidenceKind.DEEP_RESEARCH,
        title = "Evidence",
        summary = "Summary",
        content = "Content",
        sources = urls.map { url -> AgentSourceCitation(title = url, url = url) },
    )
}
