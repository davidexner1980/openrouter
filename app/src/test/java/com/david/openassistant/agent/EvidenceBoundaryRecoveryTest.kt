package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceBoundaryRecoveryTest {
    @Test
    fun focusedRecoveryAcceptsASubstantiveNegativeFinding() {
        val task = recoveryTask()
        val result = saturatedResult(task)

        assertTrue(acceptsBoundedResearchRecovery(task, result))
        assertTrue(boundedResearchRecoveryEventMessage(task).startsWith(EPISTEMIC_BOUNDARY_EVENT_PREFIX))
    }

    @Test
    fun ordinaryResearchCannotUseTheNegativeFindingEscape() {
        val task = recoveryTask().copy(id = "research_primary")

        assertFalse(acceptsBoundedResearchRecovery(task, saturatedResult(task)))
    }

    @Test
    fun focusedRecoveryMustActuallySearchAndReadSources() {
        val task = recoveryTask()
        val lazy = saturatedResult(task).copy(
            summary = AgentApiSummary(webSearchRequests = 0),
            toolExecutions = emptyList(),
        )

        assertFalse(acceptsBoundedResearchRecovery(task, lazy))
    }

    @Test
    fun plannerCriteriaDoNotAssumeDesiredEvidenceExists() {
        val criterion = AgentAcceptanceCriterion(
            id = "consensus",
            description = "The recommendation is supported by a consensus of at least 5 independent sources and a controlled comparison.",
        )
        val draft = AgentPlanDraft(
            title = "Research a product",
            objective = "Find the best-supported product.",
            finalOutputDescription = "A recommendation.",
            acceptanceCriteria = listOf(criterion),
            tasks = listOf(
                AgentTaskDraft(
                    id = "research",
                    title = "Research",
                    instructions = "Investigate the exact product and its alternatives.",
                    capability = AgentCapability.DEEP_RESEARCH,
                    dependsOn = emptyList(),
                    weight = 1.0,
                    acceptanceCriteria = listOf(criterion),
                ),
            ),
        )

        val bounded = boundEvidenceContingentPlanCriteria(draft)

        assertTrue(bounded.acceptanceCriteria.single().description.contains("strongest supportable answer"))
        assertTrue(bounded.tasks.single().acceptanceCriteria.single().description.contains("do not invent"))
        assertTrue(
            boundEvidenceContingentPlanCriteria(bounded)
                .acceptanceCriteria.single().description == bounded.acceptanceCriteria.single().description,
        )

        val pluralMetricDraft = draft.copy(
            acceptanceCriteria = listOf(
                criterion.copy(
                    id = "metrics",
                    description = "Specific measured performance metrics are required.",
                ),
            ),
            tasks = emptyList(),
        )
        assertTrue(
            boundEvidenceContingentPlanCriteria(pluralMetricDraft)
                .acceptanceCriteria.single().description.contains("evidence boundary"),
        )
    }

    private fun recoveryTask(): AgentTask {
        val findings = listOf(
            AgentAcceptanceCriterion("metric", "Find a controlled measured result."),
            AgentAcceptanceCriterion("consensus", "Find an independent consensus."),
        )
        return AgentTask(
            id = "verification_gap_closure_1",
            order = 7,
            title = "Research gap closure",
            instructions = "Close or explicitly bound the exact missing evidence.",
            capability = AgentCapability.DEEP_RESEARCH,
            acceptanceCriteria = findings,
        )
    }

    private fun saturatedResult(task: AgentTask): AgentStepResult {
        val sources = listOf(
            AgentSourceCitation("Review", "https://one.example/review"),
            AgentSourceCitation("Test", "https://two.example/test"),
            AgentSourceCitation("Discussion", "https://three.example/discussion"),
            AgentSourceCitation("Specification", "https://four.example/specification"),
        )
        val claims = listOf(
            AgentClaim(
                id = "negative_metric",
                taskId = task.id,
                text = "No controlled comparison was found after three targeted search angles.",
                type = AgentClaimType.FACT,
                confidence = 0.96,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = sources.map { it.url },
            ),
            AgentClaim(
                id = "negative_consensus",
                taskId = task.id,
                text = "The independent record contains disagreement rather than consensus.",
                type = AgentClaimType.FACT,
                confidence = 0.93,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = sources.map { it.url },
            ),
            AgentClaim(
                id = "negative_record",
                taskId = task.id,
                text = "The official record is silent on this specific revision.",
                type = AgentClaimType.FACT,
                confidence = 0.9,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = sources.map { it.url },
            ),
        )
        return AgentStepResult(
            content = (
                "The pass records exact queries, follows named-source leads, compares methods, " +
                    "documents contradictory results, and explains why no controlled comparison " +
                    "or independent consensus could be located. The answer must therefore use the " +
                    "strongest supportable recommendation and disclose this evidence boundary. "
                ).repeat(12),
            summary = AgentApiSummary(webSearchRequests = 3),
            sources = sources,
            completionScore = 0.24,
            acceptanceChecks = listOf(
                AgentAcceptanceCheck(
                    "metric",
                    AgentAcceptanceCheckStatus.FAIL,
                    0.10,
                    "Multiple targeted searches and full-source reads found no controlled measurement.",
                ),
                AgentAcceptanceCheck(
                    "consensus",
                    AgentAcceptanceCheckStatus.FAIL,
                    0.10,
                    "No independent consensus exists in the located evidence; sources explicitly disagree.",
                ),
            ),
            claims = claims,
            unresolvedQuestions = listOf("A laboratory comparison remains unavailable."),
            toolExecutions = listOf(
                AgentToolExecution(LOCAL_WEB_SEARCH_TOOL, "angle one", true),
                AgentToolExecution(PROVIDER_WEB_SEARCH_TOOL, "angle two", true),
                AgentToolExecution(LOCAL_WEB_SEARCH_TOOL, "angle three", true),
                AgentToolExecution(LOCAL_WEB_FETCH_TOOL, "full read one", true),
                AgentToolExecution(LOCAL_WEB_FETCH_TOOL, "full read two", true),
            ),
        )
    }
}
