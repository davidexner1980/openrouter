package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class ResearchRecoveryEngineTest {

    @Test
    fun `test diagnosis of repeated context`() {
        val task = AgentTask(
            order = 0,
            title = "Research",
            instructions = "Find facts",
            capability = AgentCapability.WEB_RESEARCH,
            consecutiveNoProgressCount = 2,
            lastMaterialProgressFingerprint = "f1",
            progressFingerprint = "f1"
        )
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RESEARCHING,
            plannerModelId = "model1",
            executionModelId = "openrouter/free", // isFreeRoute = true
            tasks = listOf(task)
        )

        val diagnosis = ResearchRecoveryEngine.diagnoseStall(goal, task, isFree = true, qualityAccepted = false)
        assertEquals(ExecutionStallDiagnosis.REPEATED_CONTEXT, diagnosis)
    }

    @Test
    fun `test diagnosis of duplicate query portfolio`() {
        val task = AgentTask(
            order = 0,
            title = "Research",
            instructions = "Find facts",
            capability = AgentCapability.WEB_RESEARCH,
            consecutiveNoProgressCount = 2,
            recentQueryFingerprints = listOf("q1", "q1")
        )
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RESEARCHING,
            plannerModelId = "model1",
            executionModelId = "openrouter/free",
            tasks = listOf(task)
        )

        val diagnosis = ResearchRecoveryEngine.diagnoseStall(goal, task, isFree = true, qualityAccepted = false)
        assertEquals(ExecutionStallDiagnosis.DUPLICATE_QUERY_PORTFOLIO, diagnosis)
    }

    @Test
    fun `test tactic selection avoid duplicates`() {
        val task = AgentTask(order = 0, title = "T", instructions = "I", capability = AgentCapability.WEB_RESEARCH)
        val plan1 = ResearchRecoveryPlan(
            id = "p1", goalId = "g1", taskId = task.id, inputExecutionFingerprint = "f1",
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            status = RecoveryPlanStatus.COMMITTED,
            logicalProviderRequestId = null, proposal = null, proposalFingerprint = null, validationResult = null,
            failureClassification = null, failureMessage = null
        )
        val goal = AgentGoal(
            conversationId = "c1", userRequest = "R", title = "T", objective = "O", finalOutputDescription = "D",
            status = AgentGoalStatus.RESEARCHING, plannerModelId = "m1", executionModelId = "m2",
            tasks = listOf(task),
            recoveryPlans = listOf(plan1)
        )

        val tactic = ResearchRecoveryEngine.selectTactic(goal, task, ExecutionStallDiagnosis.REPEATED_CONTEXT)
        assertNotEquals(EscalationTactic.REBUILD_QUERY_PORTFOLIO, tactic)
        assertEquals(EscalationTactic.FOLLOW_RELEVANT_LINKS, tactic)
    }

    @Test
    fun `test novelty validation`() {
        val proposal = RecoveryProposal(
            revisedInvestigationInterpretation = "I1",
            specificUnresolvedGap = "G1",
            selectedSourceFamilyShift = "S1",
            evidenceTargets = listOf("T1"),
            falsifiers = listOf("F1"),
            newQueryPortfolio = listOf("Q1"),
            followUpRule = null, rationale = "R1", expectedNoveltyDimensions = listOf("D1")
        )
        val fingerprint = FingerprintUtils.calculateProposalFingerprint(proposal)
        
        val plan1 = ResearchRecoveryPlan(
            id = "p1", goalId = "g1", taskId = "t1", inputExecutionFingerprint = "f1",
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            status = RecoveryPlanStatus.COMMITTED,
            logicalProviderRequestId = null, 
            proposal = proposal, 
            proposalFingerprint = fingerprint, 
            validationResult = null,
            failureClassification = null, failureMessage = null
        )

        val isNovel = ResearchRecoveryEngine.validateNovelty(proposal, listOf(plan1))
        assertEquals("Should reject exact duplicate proposal", false, isNovel)

        val novelProposal = proposal.copy(newQueryPortfolio = listOf("LongerNovelQuery"))
        val isNovel2 = ResearchRecoveryEngine.validateNovelty(novelProposal, listOf(plan1))
        assertEquals("Should accept proposal with new query", true, isNovel2)
    }
}
