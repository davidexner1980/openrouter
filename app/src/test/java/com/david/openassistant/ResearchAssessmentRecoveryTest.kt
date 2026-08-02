package com.david.openassistant

import com.david.openassistant.agent.AgentAcceptanceCheck
import com.david.openassistant.agent.AgentAcceptanceCheckStatus
import com.david.openassistant.agent.AgentAcceptanceCriterion
import com.david.openassistant.agent.AgentApiSummary
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentClaim
import com.david.openassistant.agent.AgentClaimSupport
import com.david.openassistant.agent.AgentClaimType
import com.david.openassistant.agent.AgentEvidence
import com.david.openassistant.agent.AgentEvidenceKind
import com.david.openassistant.agent.AgentSourceCitation
import com.david.openassistant.agent.AgentStepResult
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.agent.ResearchQualityGate
import com.david.openassistant.agent.durableEvidenceContent
import com.david.openassistant.agent.recoverResearchToolAudit
import com.david.openassistant.agent.recoverPreservedResearchAssessment
import com.david.openassistant.agent.recoverResearchAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAssessmentRecoveryTest {
    @Test
    fun sourceBackedResearchRecoversOnlyMissingProviderSelfGrade() {
        val task = researchTask()
        val recovered = recoverResearchAssessment(task, validResult(task))

        assertEquals(0.72, recovered.completionScore, 0.0001)
        assertEquals(
            listOf(AgentAcceptanceCheckStatus.PARTIAL, AgentAcceptanceCheckStatus.PARTIAL),
            recovered.acceptanceChecks.map { it.status },
        )
    }

    @Test
    fun shallowResearchAndExplicitFailureAreNeverOverridden() {
        val task = researchTask()
        val shallow = validResult(task).copy(
            sources = listOf(AgentSourceCitation("One", "https://one.example/a")),
        )
        assertEquals(0.0, recoverResearchAssessment(task, shallow).completionScore, 0.0)

        val explicitFailure = validResult(task).copy(
            acceptanceChecks = task.acceptanceCriteria.map {
                AgentAcceptanceCheck(it.id, AgentAcceptanceCheckStatus.FAIL, 0.0, "Missing evidence")
            },
        )
        assertEquals(0.0, recoverResearchAssessment(task, explicitFailure, metadataWasRepaired = true).completionScore, 0.0)

        val deliverableTask = task.copy(
            acceptanceCriteria = listOf(
                AgentAcceptanceCriterion("locations", "The final evidence names at least 5 locations with exact coordinates."),
            ),
        )
        val missingDeliverableGrade = validResult(deliverableTask).copy(
            acceptanceChecks = listOf(
                AgentAcceptanceCheck("locations", AgentAcceptanceCheckStatus.NOT_EVALUATED, 0.0, "Missing"),
            ),
        )
        assertEquals(0.0, recoverResearchAssessment(deliverableTask, missingDeliverableGrade).completionScore, 0.0)
    }

    @Test
    fun repairedLegacyCheckpointCanAdvanceWithoutAnotherProviderCall() {
        val task = researchTask().copy(status = AgentTaskStatus.FAILED, progressScore = 0.0)
        val result = validResult(task)
        val evidence = AgentEvidence(
            id = "checkpoint",
            taskId = task.id,
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = task.title,
            summary = "Preserved research",
            content = result.content + """

                Runtime note: the provider's useful work was preserved and its response envelope was repaired before deterministic quality checks.
                Autonomous local tool activity:
                - [PASS] public_web_search: request-specific branch one
                - [PASS] public_web_search: request-specific branch two
                - [PASS] public_web_search: request-specific branch three
                - [PASS] public_web_fetch: full source one
                - [PASS] public_web_fetch: full source two
                - [PASS] public_web_fetch: full source three
            """.trimIndent(),
            sources = result.sources,
        )

        val recovered = recoverPreservedResearchAssessment(task, evidence, result.claims)

        assertNotNull(recovered)
        assertEquals(0.72, recovered!!.completionScore, 0.0001)
    }

    @Test
    fun longProviderOutputCannotCrowdRuntimeResearchProofOutOfCheckpoint() {
        val task = researchTask()
        val result = validResult(task).copy(content = "Provider analysis content. ".repeat(2_000))

        val stored = durableEvidenceContent(result, maximumCharacters = 16_000)
        val runtimeAudit = stored.substringAfterLast("Autonomous local tool activity:")

        assertEquals(16_000, stored.length)
        assertTrue(runtimeAudit.contains("[PASS] public_web_fetch"))
        assertEquals(3, Regex("\\[PASS] public_web_fetch").findAll(runtimeAudit).count())
    }

    @Test
    fun checkpointAuditRehydratesActualSearchAndFetchProof() {
        val evidence = AgentEvidence(
            taskId = "research_primary",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = "Primary verification",
            summary = "Preserved",
            content = """
                Provider analysis.

                Autonomous local tool activity:
                - [PASS] public_web_search: Found records for request-specific branch one.
                - [PASS] public_web_search: Found records for request-specific branch two.
                - [PASS] public_web_search: Found records for request-specific branch three.
                - [PASS] public_web_fetch: Research full-source read: Fetched 6100 readable characters from en.wikipedia.org.
                - [ERROR] public_web_fetch: Candidate returned HTTP 404.
                - [PASS] public_web_fetch: Research full-source read: Fetched 2894 readable characters from www.dentoncounty.gov.
            """.trimIndent(),
        )

        val recovered = recoverResearchToolAudit(listOf(evidence), "research_primary")

        assertEquals(3, recovered.count { it.succeeded && it.toolName == "public_web_search" })
        assertEquals(2, recovered.count { it.succeeded && it.toolName == "public_web_fetch" })
        assertTrue(recovered.any { it.summary.contains("dentoncounty.gov") })
    }

    @Test
    fun checkpointAuditRehydratesProviderOwnedResearchProof() {
        val evidence = AgentEvidence(
            taskId = "research_discovery",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = "Discovery",
            summary = "Preserved",
            content = """
                Provider analysis.

                Autonomous local tool activity:
                - [PASS] provider_web_search: OpenRouter search one.
                - [PASS] provider_web_search: OpenRouter search two.
                - [PASS] provider_web_search: OpenRouter search three.
                - [PASS] provider_web_extract: Substantial extract from https://one.example/report.
                - [PASS] provider_web_extract: Substantial extract from https://two.example/report.
            """.trimIndent(),
        )

        val recovered = recoverResearchToolAudit(listOf(evidence), "research_discovery")

        assertEquals(3, recovered.count { it.succeeded && it.toolName == "provider_web_search" })
        assertEquals(2, recovered.count { it.succeeded && it.toolName == "provider_web_extract" })
    }

    @Test
    fun sixSubstantialProviderExtractsConservativelySatisfyThreeReadUnits() {
        val task = researchTask()
        val result = validResult(task).copy(
            toolExecutions = List(6) { index ->
                com.david.openassistant.agent.AgentToolExecution(
                    "provider_web_extract",
                    "Substantial provider extract ${index + 1} from https://domain${index + 1}.example/report.",
                    true,
                )
            },
        )

        val decision = ResearchQualityGate.evaluateStep(task, result, null)

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun successfulGovernmentPageReadIsPrimaryEvidenceWithoutMagicProviderWording() {
        val task = researchTask().copy(
            id = "research_primary",
            title = "Primary verification: construction history",
        )
        val result = validResult(task).copy(
            content = (
                "The county record identifies the builder, date, scope, and archival context. " +
                    "The evidence is compared with independent historical accounts and its remaining uncertainty. "
                ).repeat(12),
            toolExecutions = listOf(
                com.david.openassistant.agent.AgentToolExecution(
                    "public_web_search",
                    "request-specific branch one",
                    true,
                ),
                com.david.openassistant.agent.AgentToolExecution(
                    "public_web_search",
                    "request-specific branch two",
                    true,
                ),
                com.david.openassistant.agent.AgentToolExecution(
                    "public_web_search",
                    "request-specific branch three",
                    true,
                ),
                com.david.openassistant.agent.AgentToolExecution(
                    "public_web_fetch",
                    "Fetched 6100 readable characters from en.wikipedia.org.",
                    true,
                ),
                com.david.openassistant.agent.AgentToolExecution(
                    "public_web_fetch",
                    "Fetched 2894 readable characters from www.dentoncounty.gov.",
                    true,
                ),
                com.david.openassistant.agent.AgentToolExecution(
                    "public_web_fetch",
                    "Fetched 3210 readable characters from data.cityofdenton.com.",
                    true,
                ),
            ),
        )

        val decision = ResearchQualityGate.evaluateStep(task, result, null)

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun failedPrimaryMilestoneRevalidatesFromTheRealDurableGovernmentFetchAudit() {
        val baseTask = researchTask().copy(
            id = "research_primary",
            title = "Primary verification: Historical and Construction Details",
        )
        val task = baseTask.copy(
            status = AgentTaskStatus.FAILED,
            progressScore = 1.0,
            acceptanceChecks = baseTask.acceptanceCriteria.map {
                AgentAcceptanceCheck(it.id, AgentAcceptanceCheckStatus.PASS, 1.0, "Satisfied")
            },
        )
        val valid = validResult(task)
        val evidence = AgentEvidence(
            id = "primary-checkpoint",
            taskId = task.id,
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = task.title,
            summary = "Historical evidence",
            content = valid.content + """

                Autonomous local tool activity:
                - [PASS] public_web_search: branch one
                - [PASS] public_web_search: branch two
                - [PASS] public_web_search: branch three
                - [PASS] public_web_fetch: Research full-source read: Fetched 6100 readable characters from en.wikipedia.org.
                - [PASS] public_web_fetch: Rabbit-hole full-source read: Fetched 2894 readable characters from www.dentoncounty.gov.
                - [PASS] public_web_fetch: Alternate full-source read: Fetched 3210 readable characters from data.cityofdenton.com.
            """.trimIndent(),
            sources = valid.sources,
        )

        val recovered = recoverPreservedResearchAssessment(task, evidence, valid.claims)

        assertNotNull(recovered)
        assertEquals(3, recovered!!.toolExecutions.count { it.succeeded && it.toolName == "public_web_fetch" })
    }

    private fun researchTask() = AgentTask(
        id = "research_discovery",
        order = 1,
        title = "Research pass 1: map the evidence landscape",
        instructions = "Map diverse sources and preserve URLs.",
        capability = AgentCapability.DEEP_RESEARCH,
        acceptanceCriteria = listOf(
            AgentAcceptanceCriterion("landscape", "The evidence landscape is mapped with multiple relevant, non-duplicate sources."),
            AgentAcceptanceCriterion("urls", "Material factual findings preserve exact HTTPS source URLs and clearly state uncertainty."),
        ),
    )

    private fun validResult(task: AgentTask) = AgentStepResult(
        content = (
            "The evidence landscape identifies boundary metadata, official measurement records, historical estimates, and unresolved methodological uncertainty. " +
                "The exact scope remains uncertain and requires primary-source verification. "
            ).repeat(10),
        summary = AgentApiSummary(webSearchRequests = 3),
        sources = listOf(
            AgentSourceCitation("Official", "https://data.gov.example/measurement"),
            AgentSourceCitation("Local", "https://city.example/boundary-metadata"),
            AgentSourceCitation("Independent", "https://methods.example/measurement-audit"),
        ),
        completionScore = 0.0,
        acceptanceChecks = task.acceptanceCriteria.map {
            AgentAcceptanceCheck(it.id, AgentAcceptanceCheckStatus.NOT_EVALUATED, 0.0, "Provider omitted grade")
        },
        claims = listOf(
            AgentClaim(
                id = "fact-one",
                taskId = task.id,
                text = "Official and local sources document the measurement boundary.",
                type = AgentClaimType.FACT,
                confidence = 0.85,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = listOf("https://data.gov.example/measurement"),
            ),
            AgentClaim(
                id = "fact-two",
                taskId = task.id,
                text = "Local metadata provides a separate boundary record.",
                type = AgentClaimType.FACT,
                confidence = 0.8,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = listOf("https://city.example/boundary-metadata"),
            ),
            AgentClaim(
                id = "fact-three",
                taskId = task.id,
                text = "Independent audit identifies methodological uncertainty.",
                type = AgentClaimType.FACT,
                confidence = 0.75,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = listOf("https://methods.example/measurement-audit"),
            ),
        ),
        toolExecutions = listOf(
            com.david.openassistant.agent.AgentToolExecution("public_web_fetch", "Full source one", true),
            com.david.openassistant.agent.AgentToolExecution("public_web_fetch", "Full source two", true),
            com.david.openassistant.agent.AgentToolExecution("public_web_fetch", "Full source three", true),
        ),
    )
}
