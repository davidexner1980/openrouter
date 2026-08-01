package com.david.openassistant

import com.david.openassistant.agent.AgentAcceptanceCriterion
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentPlanDraft
import com.david.openassistant.agent.AgentResearchDepth
import com.david.openassistant.agent.AgentResearchPolicy
import com.david.openassistant.agent.AgentTaskDraft
import com.david.openassistant.agent.recoverNearCompletePlanTail
import com.david.openassistant.agent.researchPlanMaintainsRequestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStructureRecoveryTest {
    private val policy = AgentResearchPolicy(AgentResearchDepth.DEEP)
    private val request = "what is the best recurve bow you can buy"

    @Test
    fun exactDeviceFailureRecoversMissingGapClosureAndSynthesisWithoutClaims() {
        val recovered = recoverNearCompletePlanTail(
            draft = plan(researchCount = 3, includeSynthesis = false),
            policy = policy,
            exactRequest = request,
        )

        assertNotNull(recovered)
        assertEquals(1, recovered!!.addedResearchMilestones)
        assertEquals(true, recovered.addedSynthesisMilestone)
        assertEquals(4, recovered.plan.tasks.count { it.capability == AgentCapability.DEEP_RESEARCH })
        assertEquals(1, recovered.plan.tasks.count { it.capability == AgentCapability.SYNTHESIZE })
        assertTrue(recovered.plan.tasks.takeLast(2).all { it.instructions.contains("recurve bow") })
        assertTrue(recovered.plan.tasks.last().instructions.contains("supplies no facts of its own"))
    }

    @Test
    fun missingSynthesisOnlyIsRecoveredLocally() {
        val recovered = recoverNearCompletePlanTail(
            draft = plan(researchCount = 4, includeSynthesis = false),
            policy = policy,
            exactRequest = request,
        )

        assertEquals(0, recovered?.addedResearchMilestones)
        assertEquals(true, recovered?.addedSynthesisMilestone)
        assertEquals(AgentCapability.SYNTHESIZE, recovered?.plan?.tasks?.last()?.capability)
    }

    @Test
    fun exactRefinementFailureReclassifiesFinalReasonAsSynthesis() {
        val providerDraft = plan(researchCount = 4, includeSynthesis = true).let { draft ->
            draft.copy(
                tasks = draft.tasks.dropLast(1) + draft.tasks.last().copy(
                    id = "t5",
                    title = "Final Synthesis – Produce Ranked Recommendation with Justification",
                    capability = AgentCapability.REASON,
                ),
            )
        }

        val recovered = recoverNearCompletePlanTail(providerDraft, policy, request)

        assertNotNull(recovered)
        assertEquals(true, recovered!!.reclassifiedSynthesisMilestone)
        assertEquals(false, recovered.addedSynthesisMilestone)
        assertEquals(1, recovered.plan.tasks.count { it.capability == AgentCapability.REASON })
        assertEquals(AgentCapability.SYNTHESIZE, recovered.plan.tasks.last().capability)
        assertEquals("t5", recovered.plan.tasks.last().id)
    }

    @Test
    fun ordinaryFinalReasonIsNeverReclassifiedAsSynthesis() {
        val ordinaryReason = task(
            id = "review_notes",
            capability = AgentCapability.REASON,
            instructions = "Review recurve bow evidence notes for internal consistency without claiming to perform or replace the final evidence synthesis milestone.",
        ).copy(title = "Internal consistency review")
        val providerDraft = plan(researchCount = 4, includeSynthesis = false).let { draft ->
            draft.copy(tasks = draft.tasks + ordinaryReason)
        }

        val recovered = recoverNearCompletePlanTail(providerDraft, policy, request)

        assertNotNull(recovered)
        assertEquals(false, recovered!!.reclassifiedSynthesisMilestone)
        assertEquals(true, recovered.addedSynthesisMilestone)
        assertEquals(AgentCapability.REASON, recovered.plan.tasks.first { it.id == "review_notes" }.capability)
    }

    @Test
    fun laterResearchRolesMayReferToCandidatesEstablishedByDiscovery() {
        val research = plan(researchCount = 4, includeSynthesis = false).tasks
            .filter { it.capability == AgentCapability.DEEP_RESEARCH }
            .mapIndexed { index, task ->
                if (index == 0) {
                    task
                } else {
                    task.copy(
                        title = listOf(
                            "unused",
                            "Primary-source verification of shortlisted candidates",
                            "Adversarial contradiction review of leading candidates",
                            "Gap and freshness closure for the ranked models",
                        )[index],
                        instructions = "Follow the dependency chain to verify the shortlisted candidates, measurements, evidence conflicts, market availability, and remaining uncertainty for this pass. Preserve URLs and explain any answer-changing discrepancy.",
                    )
                }
            }

        assertEquals(true, researchPlanMaintainsRequestContext(request, research))
    }

    @Test
    fun explicitlyMisorderedResearchRolesAreRejected() {
        val research = plan(researchCount = 4, includeSynthesis = false).tasks
            .filter { it.capability == AgentCapability.DEEP_RESEARCH }
            .mapIndexed { index, task ->
                when (index) {
                    1 -> task.copy(title = "Adversarial contradiction: recurve bow candidates")
                    2 -> task.copy(title = "Primary-source verification: recurve bow candidates")
                    else -> task
                }
            }

        assertEquals(false, researchPlanMaintainsRequestContext(request, research))
    }

    @Test
    fun substantiallyIncompletePlanStillRequiresProviderRecovery() {
        assertNull(
            recoverNearCompletePlanTail(
                draft = plan(researchCount = 1, includeSynthesis = false),
                policy = policy,
                exactRequest = request,
            ),
        )
    }

    @Test
    fun generic_near_complete_plan_is_not_recovered() {
        val generic = plan(researchCount = 3, includeSynthesis = false).copy(
            title = "Generic investigation",
            objective = "Investigate a subject with a reusable process.",
            finalOutputDescription = "A general answer.",
            acceptanceCriteria = listOf(AgentAcceptanceCriterion("goal", "The generic process is complete.")),
            tasks = plan(researchCount = 3, includeSynthesis = false).tasks.mapIndexed { index, task ->
                task.copy(
                    title = "Generic step ${index + 1}",
                    instructions = "Perform a generic investigation step using a reusable process, preserve generic evidence, review generic limitations, and record generic uncertainties for later analysis.",
                    acceptanceCriteria = listOf(
                        AgentAcceptanceCriterion("generic_$index", "The generic investigation step is complete."),
                    ),
                )
            },
        )

        assertNull(recoverNearCompletePlanTail(generic, policy, request))
    }

    @Test
    fun missing_middle_role_is_not_mistaken_for_a_missing_tail() {
        val wrongPrefix = plan(researchCount = 3, includeSynthesis = false).let { draft ->
            val thirdResearchId = draft.tasks.filter { it.capability == AgentCapability.DEEP_RESEARCH }[2].id
            draft.copy(
                tasks = draft.tasks.map { task ->
                    if (task.id == thirdResearchId) {
                        task.copy(title = "Gap and freshness closure: recurve bow evidence")
                    } else {
                        task
                    }
                },
            )
        }

        assertNull(recoverNearCompletePlanTail(wrongPrefix, policy, request))
    }

    private fun plan(researchCount: Int, includeSynthesis: Boolean): AgentPlanDraft {
        val tasks = mutableListOf(
            task("reason", AgentCapability.REASON, "Interpret recurve bow use cases, fit, price, and the meaning of best for the buyer."),
        )
        repeat(researchCount) { index ->
            tasks += task(
                id = "research_${index + 1}",
                capability = AgentCapability.DEEP_RESEARCH,
                instructions = "Investigate recurve bow candidate evidence for pass ${index + 1}, preserving manufacturer specifications, measured performance, buyer fit, prices, source URLs, and unresolved limitations.",
            ).copy(
                title = "${listOf("Discovery", "Primary-source verification", "Adversarial contradiction", "Gap and freshness closure")[index]}: recurve bow work",
            )
        }
        if (includeSynthesis) {
            tasks += task("synthesize", AgentCapability.SYNTHESIZE, "Rank the verified recurve bow candidates and explain fit, tradeoffs, sources, and remaining uncertainty.")
        }
        return AgentPlanDraft(
            title = "Best recurve bow purchase investigation",
            objective = "Determine which currently purchasable recurve bow is best for the buyer's actual use case and constraints.",
            finalOutputDescription = "A source-backed recommendation with category winners, fit constraints, prices, and limitations.",
            acceptanceCriteria = listOf(AgentAcceptanceCriterion("goal", "The recommendation is evidence-backed.")),
            tasks = tasks,
        )
    }

    private fun task(
        id: String,
        capability: AgentCapability,
        instructions: String,
    ) = AgentTaskDraft(
        id = id,
        title = "$id recurve bow work",
        instructions = instructions,
        capability = capability,
        dependsOn = emptyList(),
        weight = 1.0,
        acceptanceCriteria = listOf(AgentAcceptanceCriterion("${id}_criterion", "The recurve bow work is complete.")),
    )
}
