package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class ResearchRecoveryEngineTest {

    @Test
    fun repeatedContextSelectsTacticPivot() {
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "Who is the CEO of OpenAI?",
            title = "Research CEO",
            objective = "Find the CEO of OpenAI",
            finalOutputDescription = "The name of the CEO.",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "gpt-4o",
            executionModelId = "gpt-4o",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    order = 0,
                    title = "Research",
                    instructions = "Search for CEO",
                    capability = AgentCapability.WEB_RESEARCH,
                    lastRequestFingerprint = "fp1",
                    attemptCount = 1,
                    cycleId = "cycle-1"
                )
            ),
            researchCycles = listOf(
                ResearchCycle(
                    id = "cycle-1",
                    ordinal = 0,
                    status = ResearchCycleStatus.ACTIVE
                )
            ),
            activeResearchCycleId = "cycle-1"
        )
        val task = goal.tasks[0]
        val currentFingerprint = "fp1"

        val decision = ResearchRecoveryEngine.diagnoseAndSelectTactic(goal, goal.researchCycles[0], task, currentFingerprint)

        assertNotNull(decision)
        assertEquals(ExecutionStallDiagnosis.REPEATED_CONTEXT, decision!!.diagnosis)
        assertEquals(RecoveryKind.TACTIC_PIVOT, decision!!.kind)
        assertTrue(decision!!.tactic in listOf(EscalationTactic.REBUILD_QUERY_PORTFOLIO, EscalationTactic.SHIFT_SOURCE_FAMILY))
    }

    @Test
    fun advancesCycleWhenTacticsExhausted() {
        val exhaustedCycle = ResearchCycle(
            id = "cycle-1",
            ordinal = 0,
            status = ResearchCycleStatus.ACTIVE,
            learningSummary = ResearchCycleLearningSummary(
                attemptedTactics = listOf(
                    EscalationTactic.REBUILD_QUERY_PORTFOLIO,
                    EscalationTactic.SHIFT_SOURCE_FAMILY,
                    EscalationTactic.FOLLOW_CITATIONS,
                    EscalationTactic.DECOMPOSE_UNRESOLVED_GAP
                )
            )
        )
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "Who is the CEO of OpenAI?",
            title = "Research CEO",
            objective = "Find the CEO of OpenAI",
            finalOutputDescription = "The name of the CEO.",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "gpt-4o",
            executionModelId = "gpt-4o",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    order = 0,
                    title = "Research",
                    instructions = "Search for CEO",
                    capability = AgentCapability.WEB_RESEARCH,
                    lastRequestFingerprint = "fp1",
                    attemptCount = 1,
                    cycleId = "cycle-1"
                )
            ),
            researchCycles = listOf(exhaustedCycle),
            activeResearchCycleId = "cycle-1"
        )
        val task = goal.tasks[0]
        val currentFingerprint = "fp1"

        val decision = ResearchRecoveryEngine.diagnoseAndSelectTactic(goal, exhaustedCycle, task, currentFingerprint)

        assertNotNull(decision)
        assertEquals(RecoveryKind.CYCLE_ADVANCE, decision!!.kind)
        assertEquals(EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE, decision!!.tactic)
    }

    @Test
    fun identifiesSourceHomogeneity() {
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "Research AAPL",
            title = "AAPL",
            objective = "Find AAPL info",
            finalOutputDescription = "Info",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "gpt-4o",
            executionModelId = "gpt-4o",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    order = 0,
                    title = "Research",
                    instructions = "Search AAPL",
                    capability = AgentCapability.WEB_RESEARCH,
                    attemptCount = 1,
                    cycleId = "cycle-1"
                )
            ),
            evidence = listOf(
                AgentEvidence(
                    taskId = "task-1",
                    kind = AgentEvidenceKind.WEB_RESEARCH,
                    title = "Source 1",
                    summary = "Info",
                    content = "Info",
                    sources = listOf(AgentSourceCitation(title = "T", url = "https://example.com/1")),
                    cycleId = "cycle-1"
                )
            ),
            researchCycles = listOf(
                ResearchCycle(id = "cycle-1", ordinal = 0, status = ResearchCycleStatus.ACTIVE)
            ),
            activeResearchCycleId = "cycle-1"
        )
        
        val decision = ResearchRecoveryEngine.diagnoseAndSelectTactic(goal, goal.researchCycles[0], goal.tasks[0], "new-fp")
        
        assertNotNull(decision)
        assertEquals(ExecutionStallDiagnosis.SOURCE_HOMOGENEITY, decision!!.diagnosis)
    }
}
