package com.david.openassistant.agent

import java.util.Locale

/**
 * Last-resort mission planner used only after provider planning produced
 * parseable but non-durable work. It creates no findings, claims, sources, or
 * answers; it only builds request-specific milestones so the normal research
 * executor and quality gates can do the actual investigation.
 */
internal object DeterministicPlanFallback {
    fun build(goal: AgentGoal, policy: AgentResearchPolicy): AgentPlanDraft {
        val subject = extractCompactAnchor(goal.userRequest).ifBlank { goal.title.ifBlank { "the request" } }
        val normalized = goal.userRequest.lowercase(Locale.US)
        val financial = normalized.hasAnyFinancialTerm()
        val safety = normalized.hasAnySafetyTerm()
        val highStakes = financial || safety

        val passes = if (policy.requiresResearch) {
            policy.minimumPasses.coerceAtLeast(AgentResearchDepth.STANDARD.minimumPasses)
        } else {
            AgentResearchDepth.STANDARD.minimumPasses
        }
        val goalCriteria = buildList {
            add(
                AgentAcceptanceCriterion(
                    id = "goal_request_fidelity",
                    description = "The final answer directly addresses '${goal.userRequest.take(180)}' without substituting a generic neighboring question.",
                    weight = 1.4,
                ),
            )
            add(
                AgentAcceptanceCriterion(
                    id = "goal_evidence_traceability",
                    description = "Every material factual claim is tied to preserved source URLs, provenance, dates when relevant, and explicit uncertainty.",
                    weight = 1.5,
                ),
            )
            add(
                AgentAcceptanceCriterion(
                    id = "goal_contradiction_handling",
                    description = "Competing explanations, contradictory evidence, and answer-changing caveats are actively tested and reconciled or left explicit.",
                    weight = 1.25,
                ),
            )
            if (financial) {
                add(
                    AgentAcceptanceCriterion(
                        id = "goal_financial_safety",
                        description = "Financial analysis is educational and evidence-based, avoids personalized financial advice, and separates current facts from risk, uncertainty, and assumptions.",
                        weight = 1.6,
                    ),
                )
            }
        }

        val tasks = mutableListOf<AgentTaskDraft>()
        tasks += AgentTaskDraft(
            id = "interpret_request",
            title = "Interpret the request about $subject",
            instructions = buildString {
                append("Restate the user's exact objective, domain, ambiguities, definitions, decision target, and evidence needs for: ${goal.userRequest}. ")
                append("Identify what would change the answer and what must be verified before synthesis.")
                if (financial) append(" Treat this as high-stakes financial research: do not make personalized recommendations, and require dated evidence for current market or price claims.")
            },
            capability = AgentCapability.REASON,
            dependsOn = emptyList(),
            weight = 1.0,
            acceptanceCriteria = listOf(
                AgentAcceptanceCriterion(
                    id = "interpret_scope",
                    description = "The interpretation names the actual subject '$subject', key ambiguities, and answer-changing evidence requirements.",
                    weight = 1.0,
                ),
            ),
        )

        val roles = researchRoles(passes, financial, highStakes)
        var previousId = "interpret_request"
        roles.forEach { role ->
            val id = role.id
            tasks += AgentTaskDraft(
                id = id,
                title = "${role.title}: $subject",
                instructions = buildString {
                    append(role.instructions.replace("{subject}", subject).replace("{request}", goal.userRequest))
                    append(" Preserve exact HTTPS URLs, source titles, dates, methods, and uncertainty. Follow evidence-derived leads instead of repeating the original query.")
                },
                capability = AgentCapability.DEEP_RESEARCH,
                dependsOn = listOf(previousId),
                weight = role.weight,
                acceptanceCriteria = role.criteria.mapIndexed { index, criterion ->
                    AgentAcceptanceCriterion(
                        id = "${id}_criterion_${index + 1}",
                        description = criterion.replace("{subject}", subject).replace("{request}", goal.userRequest),
                        weight = if (financial) 1.25 else 1.0,
                    )
                },
            )
            previousId = id
        }

        tasks += AgentTaskDraft(
            id = "synthesize_result",
            title = "Synthesize the verified answer for $subject",
            instructions = buildString {
                append("Use only the preserved evidence, source reads, accepted claims, contradictions, and uncertainty from the completed milestones to answer: ${goal.userRequest}. ")
                append("Separate direct findings, reasoning, caveats, source-backed evidence, and unresolved questions. ")
                if (financial) append("Do not present the result as personalized financial advice; include risks, assumptions, and what a user should verify with a qualified professional when appropriate.")
            },
            capability = AgentCapability.SYNTHESIZE,
            dependsOn = listOf(previousId),
            weight = 2.5,
            acceptanceCriteria = listOf(
                AgentAcceptanceCriterion(
                    id = "synthesis_answer",
                    description = "The final result answers the user's request using source-backed findings and clearly separates fact, inference, recommendation, uncertainty, and unresolved gaps.",
                    weight = 1.4,
                ),
                AgentAcceptanceCriterion(
                    id = "synthesis_citations",
                    description = "Material factual claims are supported by preserved evidence references and no unsupported current, financial, safety, or scientific claim is presented as settled fact.",
                    weight = 1.4,
                ),
            ),
        )

        return AgentPlanDraft(
            title = goal.title.ifBlank { "Research: $subject" }.take(120),
            objective = "Investigate '${goal.userRequest.take(220)}' with durable evidence, contradiction checks, and a transparent final synthesis.",
            finalOutputDescription = goal.finalOutputDescription.ifBlank {
                "A source-grounded research answer with evidence, reasoning, caveats, and unresolved questions."
            },
            acceptanceCriteria = goalCriteria,
            tasks = tasks,
        )
    }

    private fun researchRoles(
        passes: Int,
        financial: Boolean,
        highStakes: Boolean,
    ): List<FallbackResearchRole> {
        val base = mutableListOf(
            FallbackResearchRole(
                id = "research_discovery",
                title = "Discovery",
                instructions = "Map the evidence landscape, terminology, candidate explanations, core entities, and unanswered branches for {subject} in the request: {request}.",
                criteria = listOf(
                    "Multiple non-duplicate sources map the evidence landscape for {subject}.",
                    "The pass identifies material unknowns and promising follow-up leads specific to {request}.",
                ),
                weight = 1.6,
            ),
            FallbackResearchRole(
                id = "research_primary",
                title = if (financial) "Current primary-source verification" else "Primary-source verification",
                instructions = if (financial) {
                    "Trace current facts, prices, financial metrics, provider terms, risks, fees, and claims about {subject} to first-party, regulatory, filing, official, or otherwise dated high-quality sources."
                } else {
                    "Trace material claims about {subject} to the strongest available first-party, original, official, measurement, dataset, paper, standards, or source-code evidence."
                },
                criteria = listOf(
                    "Material claims about {subject} are checked against the strongest available primary or original evidence.",
                    if (financial) "Current financial facts use dated sources and avoid stale market, price, performance, fee, or risk assumptions." else "The source method, scope, and limitations are understood.",
                ),
                weight = if (financial) 2.1 else 1.9,
            ),
            FallbackResearchRole(
                id = "research_contradictions",
                title = "Adversarial review",
                instructions = "Try to disprove or weaken the emerging answer about {subject} by pursuing contradictory evidence, alternate definitions, boundary cases, methodological weaknesses, and plausible competing explanations.",
                criteria = listOf(
                    "Counterevidence and answer-changing alternatives for {subject} are actively sought.",
                    "Contradictions are reconciled or explicitly carried into synthesis as uncertainty.",
                ),
                weight = if (highStakes) 2.0 else 1.8,
            ),
        )
        if (passes >= 4) {
            base += FallbackResearchRole(
                id = "research_gap_closure",
                title = if (financial) "Freshness and risk gap closure" else "Gap closure",
                instructions = if (financial) {
                    "Audit unresolved evidence gaps, stale sources, current financial risks, fees, performance windows, regulatory details, and missing caveats for {subject}; pursue only the gaps that could change the answer."
                } else {
                    "Audit unresolved citations, entities, discrepancies, stale facts, weak links, and unanswered branches for {subject}; pursue only the gaps that could change the answer."
                },
                criteria = listOf(
                    "Every material gap generated by the investigation is resolved with evidence or documented with consequence.",
                    if (financial) "Risk, uncertainty, and freshness caveats are explicit before synthesis." else "Unresolved uncertainty is bounded before synthesis.",
                ),
                weight = if (financial) 2.0 else 1.8,
            )
        }
        return base.take(passes)
    }

    private fun String.hasAnyFinancialTerm(): Boolean =
        containsTerm("financial") ||
            containsTerm("financials") ||
            containsTerm("finance") ||
            containsTerm("market") ||
            containsTerm("stock") ||
            containsTerm("investment") ||
            containsTerm("invest") ||
            containsTerm("portfolio") ||
            containsTerm("valuation") ||
            containsTerm("revenue") ||
            containsTerm("earnings") ||
            containsTerm("profit") ||
            containsTerm("profitability") ||
            containsTerm("debt") ||
            containsTerm("cashflow") ||
            containsTerm("price") ||
            containsTerm("cost") ||
            containsTerm("loan") ||
            containsTerm("mortgage") ||
            containsTerm("bank") ||
            containsTerm("crypto") ||
            containsTerm("fund") ||
            containsTerm("yield") ||
            contains("cash flow")

    private fun String.hasAnySafetyTerm(): Boolean =
        containsTerm("safe") ||
            containsTerm("safest") ||
            containsTerm("safety") ||
            containsTerm("risk") ||
            containsTerm("danger") ||
            containsTerm("hazard") ||
            containsTerm("recall")

    private fun String.containsTerm(term: String): Boolean {
        val escaped = Regex.escape(term.lowercase(Locale.US))
        return Regex("(^|[^a-z0-9])$escaped([^a-z0-9]|$)").containsMatchIn(this)
    }

    private data class FallbackResearchRole(
        val id: String,
        val title: String,
        val instructions: String,
        val criteria: List<String>,
        val weight: Double,
    )
}
