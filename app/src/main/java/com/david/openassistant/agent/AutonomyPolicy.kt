package com.david.openassistant.agent

import java.util.Locale

/**
 * Product-level defaults for the autonomous runtime. The app is intentionally
 * aggressive about planning, research, deterministic tools, verification, and
 * recovery while remaining conservative about irreversible Android/device
 * actions and dynamic executable code.
 */
data class AutonomyPolicy(
    val autopilotEnabled: Boolean = true,
    val deepResearchByDefault: Boolean = true,
    val autoExecuteLocalTools: Boolean = true,
    val toolFoundryEnabled: Boolean = true,
    val independentResearchAgents: Int = 4,
    val minimumResearchPasses: Int = 4,
    val minimumResearchSources: Int = 8,
    val minimumResearchDomains: Int = 4,
    val minimumSourcesPerResearchPass: Int = 3,
    val minimumNovelSourcesPerResearchPass: Int = 1,
    val minimumSearchQueriesPerResearchPass: Int = 3,
    val targetFullSourceReadsPerResearchPass: Int = 3,
    val requireContradictionSearch: Boolean = true,
    val requirePrimarySourcesWhenAvailable: Boolean = true,
) {
    init {
        require(independentResearchAgents in 1..6)
        require(minimumResearchPasses in 2..10)
        require(minimumResearchSources in 4..40)
        require(minimumResearchDomains in 2..30)
        require(minimumSourcesPerResearchPass in 1..12)
        require(minimumNovelSourcesPerResearchPass in 1..6)
        require(minimumSearchQueriesPerResearchPass in 2..12)
        require(targetFullSourceReadsPerResearchPass in 1..8)
    }

    companion object {
        val DEFAULT = AutonomyPolicy()
    }
}

enum class AgentResearchDepth(
    val wireName: String,
    val minimumPasses: Int,
    val minimumDistinctSources: Int,
) {
    NONE("none", 0, 0),
    STANDARD("standard", 3, 6),
    DEEP("deep", 4, 8),
}

data class AgentResearchPolicy(
    val depth: AgentResearchDepth = AgentResearchDepth.NONE,
    val minimumPasses: Int = depth.minimumPasses,
    val minimumDistinctSources: Int = depth.minimumDistinctSources,
    val requirePrimarySourcePass: Boolean = depth != AgentResearchDepth.NONE,
    val requireContradictionPass: Boolean = depth != AgentResearchDepth.NONE,
) {
    val requiresResearch: Boolean
        get() = depth != AgentResearchDepth.NONE

    companion object {
        fun forRequest(
            request: String,
            deepResearchByDefault: Boolean = AutonomyPolicy.DEFAULT.deepResearchByDefault,
        ): AgentResearchPolicy {
            val normalized = request.lowercase(Locale.US)
            if (NO_WEB_PATTERN.containsMatchIn(normalized)) return AgentResearchPolicy()
            if (
                (DETERMINISTIC_LOCAL_PATTERN.containsMatchIn(normalized) || ARITHMETIC_QUESTION_PATTERN.matches(normalized)) &&
                !EXTERNAL_FACT_PATTERN.containsMatchIn(normalized)
            ) return AgentResearchPolicy()
            if (
                CREATIVE_LOCAL_PATTERN.containsMatchIn(normalized) &&
                !EXTERNAL_FACT_PATTERN.containsMatchIn(normalized)
            ) return AgentResearchPolicy()
            if (DEEP_RESEARCH_PATTERN.containsMatchIn(normalized)) return AgentResearchPolicy(AgentResearchDepth.DEEP)
            if (EXTERNAL_FACT_PATTERN.containsMatchIn(normalized)) return AgentResearchPolicy(AgentResearchDepth.DEEP)
            if (FACT_SEEKING_QUESTION_PATTERN.containsMatchIn(normalized)) return AgentResearchPolicy(AgentResearchDepth.DEEP)
            // This product is research-first, not a generic chat surface. Any
            // remaining non-empty request enters durable deep research unless
            // it was explicitly classified as local/offline above. Casual
            // greetings are filtered by AutomationRouter before execution.
            return if (deepResearchByDefault && normalized.isNotBlank()) {
                AgentResearchPolicy(AgentResearchDepth.DEEP)
            } else {
                AgentResearchPolicy()
            }
        }

        private val NO_WEB_PATTERN = Regex(
            "\\b(no web|do not search|don't search|offline only|without browsing|without web|local only)\\b",
        )
        private val DEEP_RESEARCH_PATTERN = Regex(
            "\\b(deep research|deeply research|thorough research|exhaustive|investigate|research this|research into|compare sources|verify sources|fact[- ]?check)\\b",
        )
        private val EXTERNAL_FACT_PATTERN = Regex(
            "\\b(latest|current|today|recent|news|price|cost|law|legal|regulation|standard|schedule|weather|score|market|research|paper|study|source|citation|web search|look up|find online|recommend|best|compare|versus|review|specification|version|release|availability|near me|documentation|medical|financial|financials|finance|investment|stock|valuation|revenue|earnings|profit|profitability|debt|cash flow|dividend|yield|crypto|fund|elevation|altitude|topography|topographic|geography|geographic|landmass|coordinate|coordinates|latitude|longitude|highest|lowest|largest|smallest|oldest|newest|maximum|minimum|record)\\b",
        )
        private val FACT_SEEKING_QUESTION_PATTERN = Regex(
            "(^|[.!?]\\s*)(what|which|who|whose|where|when|why|is|are|was|were|do|does|did|can|could|should|would|how(?:\\s+(?:high|far|old|large|many|much|often|long))?)\\b[^?]{2,}[?]?$",
        )
        private val ARITHMETIC_QUESTION_PATTERN = Regex(
            "^\\s*(?:what (?:is|are)|calculate|compute)?\\s*[-+]?\\d[\\d\\s().+\\-*/%^]*[?]?\\s*$",
        )
        private val DETERMINISTIC_LOCAL_PATTERN = Regex(
            "\\b(calculate|calculator|equation|convert|conversion|how many|count words|count characters|date difference|days between|time in|timezone|format json|validate json|hash|sha[- ]?256|base64|url encode|url decode|sort lines|deduplicate lines|statistics|average|median|uuid|read workspace|write workspace|search workspace|list workspace|workspace file)\\b",
        )
        private val CREATIVE_LOCAL_PATTERN = Regex(
            "\\b(translate|rewrite|proofread|fix (the )?grammar|summarize (this|the following|the provided|the pasted)|format (this|the following|the provided|the pasted)|roleplay)\\b|" +
                "\\bwrite\\b[^\\n]{0,60}\\b(poem|story|fiction|fictional|letter|email|speech|scene|song|screenplay|novel)\\b|" +
                "\\bbrainstorm\\b[^\\n]{0,40}\\b(fictional|creative|story|character|plot|idea)s?\\b",
        )
    }
}

enum class AutomationRoute {
    DIRECT_CHAT,
    TOOL_ASSISTED_CHAT,
    AUTONOMOUS_GOAL,
}

data class AutomationDecision(
    val route: AutomationRoute,
    val researchPolicy: AgentResearchPolicy,
    val reason: String,
)

/**
 * Research-first routing. Greetings and explicitly local deterministic work
 * may stay immediate; every substantive factual inquiry enters the durable
 * multi-pass research runtime without a mode button.
 */
object AutomationRouter {
    fun decide(
        request: String,
        hasImage: Boolean,
        modelSupportsTools: Boolean,
        policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
    ): AutomationDecision {
        val normalized = request.trim().lowercase(Locale.US)
        val researchPolicy = AgentResearchPolicy.forRequest(normalized, policy.deepResearchByDefault)
        if (!policy.autopilotEnabled || hasImage) {
            return AutomationDecision(
                route = AutomationRoute.DIRECT_CHAT,
                researchPolicy = researchPolicy,
                reason = if (hasImage) "Image turns remain interactive." else "Autopilot is disabled by policy.",
            )
        }
        if (normalized.isBlank() || CASUAL_PATTERN.matches(normalized) || SafetyClassifier.isInternalMetadata(normalized)) {
            return AutomationDecision(
                route = if (modelSupportsTools) AutomationRoute.TOOL_ASSISTED_CHAT else AutomationRoute.DIRECT_CHAT,
                researchPolicy = AgentResearchPolicy(),
                reason = "Simple conversation or internal metadata.",
            )
        }
        if (LOCAL_CREATIVE_PATTERN.containsMatchIn(normalized) && !researchPolicy.requiresResearch) {
            return AutomationDecision(
                route = if (modelSupportsTools) AutomationRoute.TOOL_ASSISTED_CHAT else AutomationRoute.DIRECT_CHAT,
                researchPolicy = AgentResearchPolicy(),
                reason = "Supplied-text and creative work.",
            )
        }
        if (modelSupportsTools && LOCAL_TOOL_PATTERN.containsMatchIn(normalized) && !researchPolicy.requiresResearch) {
            return AutomationDecision(
                route = AutomationRoute.TOOL_ASSISTED_CHAT,
                researchPolicy = AgentResearchPolicy(),
                reason = "One or more deterministic local tools can complete the request immediately.",
            )
        }

        val looksMultiStep = ACTION_PATTERN.containsMatchIn(normalized) ||
            normalized.length >= 150 ||
            normalized.count { it == '\n' } >= 2 ||
            normalized.count { it == ',' } >= 3
        
        return if (researchPolicy.requiresResearch || looksMultiStep) {
            AutomationDecision(
                route = AutomationRoute.AUTONOMOUS_GOAL,
                researchPolicy = researchPolicy,
                reason = if (researchPolicy.requiresResearch) {
                    "The request requires source-backed multi-pass research."
                } else {
                    "The request benefits from planning, durable execution, recovery, and verification."
                },
            )
        } else if (modelSupportsTools) {
            AutomationDecision(
                route = AutomationRoute.TOOL_ASSISTED_CHAT,
                researchPolicy = researchPolicy,
                reason = "The request can be completed interactively with tool support.",
            )
        } else {
            AutomationDecision(
                route = AutomationRoute.DIRECT_CHAT,
                researchPolicy = researchPolicy,
                reason = "The request can be completed interactively.",
            )
        }
    }

    private val CASUAL_PATTERN = Regex(
        "^(hi|hello|hey|thanks|thank you|ok|okay|cool|great|yes|no|lol|how are you|what can you do)[.!? ]*$",
    )
    private val LOCAL_TOOL_PATTERN = Regex(
        "\\b(calculate|calculator|equation|convert|conversion|how many|count words|count characters|date difference|days between|time in|timezone|format json|validate json|hash|sha[- ]?256|base64|url encode|sort lines|statistics|average|median|uuid|read workspace|write workspace|search workspace|list workspace)\\b",
    )
    private val LOCAL_CREATIVE_PATTERN = Regex(
        "\\b(translate|rewrite|proofread|fix (the )?grammar|summarize (this|the following|the provided|the pasted)|format (this|the following|the provided|the pasted)|roleplay)\\b|" +
            "\\bwrite\\b[^\\n]{0,60}\\b(poem|story|fiction|fictional|letter|email|speech|scene|song|screenplay|novel)\\b|" +
            "\\bbrainstorm\\b[^\\n]{0,40}\\b(fictional|creative|story|character|plot|idea)s?\\b",
    )
    private val ACTION_PATTERN = Regex(
        "\\b(make|build|create|design|develop|implement|fix|debug|improve|upgrade|automate|plan|analyze|audit|review|verify|investigate|research|compare|find|recommend|write a program|go through|keep working|complete|solve|generate|refactor)\\b",
    )
}

/**
 * Adds deterministic research quality contracts without replacing the
 * planner's request-specific content. A malformed or generic plan is rejected
 * upstream; this layer never invents stock subject matter as a fallback.
 */
object AgentPlanEnhancer {
    fun enhance(
        draft: AgentPlanDraft,
        policy: AgentResearchPolicy,
    ): AgentPlanDraft {
        if (!policy.requiresResearch) return draft

        val reasoningPrelude = draft.tasks.filter { it.capability == AgentCapability.REASON }
        val plannerResearch = draft.tasks.filter { it.capability == AgentCapability.DEEP_RESEARCH }
        val deferredToolTasks = draft.tasks.filter {
            it.capability in setOf(AgentCapability.TOOL_USE, AgentCapability.TOOL_CREATE)
        }
        val synthesis = draft.tasks.lastOrNull { it.capability == AgentCapability.SYNTHESIZE }

        require(reasoningPrelude.isNotEmpty()) {
            "Deep research requires a request-specific interpretation milestone; no generic fallback is permitted."
        }
        require(plannerResearch.size == policy.minimumPasses) {
            "Deep research requires exactly ${policy.minimumPasses} request-specific research milestones."
        }
        require(synthesis != null) {
            "Deep research requires a request-specific synthesis milestone."
        }

        val normalizedPrelude = reasoningPrelude.mapIndexed { index, task ->
            task.copy(dependsOn = if (index == 0) emptyList() else listOf(reasoningPrelude[index - 1].id))
        }
        val usedIds = (normalizedPrelude + deferredToolTasks).mapTo(mutableSetOf()) { it.id }
        val contracts = researchRoleContracts(policy)
        val researchTasks = mutableListOf<AgentTaskDraft>()
        var previousId = normalizedPrelude.last().id
        plannerResearch.zip(contracts).forEach { (plannedTask, contract) ->
            val id = uniqueId(contract.id, usedIds)
            researchTasks += plannedTask.copy(
                id = id,
                title = "${contract.titlePrefix}: ${plannedTask.title}",
                instructions = buildString {
                    appendLine(plannedTask.instructions)
                    appendLine()
                    appendLine("Epistemic role contract: ${contract.protocol}")
                    appendLine("Keep the subject-specific investigation above. Derive every query from the actual unresolved question or a lead found in evidence; do not substitute a reusable query template. Read full sources, preserve exact HTTPS URLs, and treat snippets only as leads.")
                }.trim(),
                capability = AgentCapability.DEEP_RESEARCH,
                dependsOn = listOf(previousId),
                weight = maxOf(plannedTask.weight, contract.minimumWeight),
                acceptanceCriteria = (plannedTask.acceptanceCriteria + listOf(
                    AgentAcceptanceCriterion(
                        id = "${id}_role",
                        description = contract.acceptance,
                        weight = 1.0,
                    ),
                    AgentAcceptanceCriterion(
                        id = "${id}_traceability",
                        description = "Material findings preserve exact source URLs, provenance, scope, method, dates when relevant, and explicit uncertainty.",
                        weight = 1.0,
                    ),
                    AgentAcceptanceCriterion(
                        id = "${id}_depth",
                        description = "The pass records independently reasoned information needs, analyzes full source material, and follows newly discovered leads instead of stopping at the first plausible result.",
                        weight = 1.25,
                    ),
                )).distinctBy { it.id },
            )
            previousId = id
        }

        // Subject-specific computation and Tool Foundry work consumes the
        // researched evidence; it cannot replace the investigation.
        val normalizedDeferredTools = deferredToolTasks.map { task ->
            task.copy(dependsOn = listOf(previousId)).also { previousId = task.id }
        }

        val synthesisId = uniqueId(synthesis.id, usedIds)
        val synthesisEvidenceDescription = researchSynthesisEvidenceDescription(
            researchTasks.map(::researchPassRole).toSet(),
        ) ?: "The final result reconciles every required research role."
        val finalTask = synthesis.copy(
            id = synthesisId,
            instructions = buildString {
                appendLine(synthesis.instructions)
                appendLine()
                appendLine("Use the request-specific evidence model produced by the investigation. Reconcile disagreements, distinguish fact from inference, cite preserved evidence, and expose unresolved uncertainty; do not fall back to a generic answer format.")
            }.trim(),
            dependsOn = listOf(previousId),
            weight = maxOf(synthesis.weight, 2.5),
            acceptanceCriteria = (synthesis.acceptanceCriteria + listOf(
                AgentAcceptanceCriterion(
                    id = "${synthesisId}_source_synthesis",
                    description = synthesisEvidenceDescription,
                    weight = 1.5,
                ),
                AgentAcceptanceCriterion(
                    id = "${synthesisId}_request_fidelity",
                    description = "The final output resolves the actual decision target and ambiguities in the user's request rather than substituting a generic neighboring question.",
                    weight = 1.25,
                ),
            )).distinctBy { it.id },
        )

        return draft.copy(
            acceptanceCriteria = (draft.acceptanceCriteria + deepResearchCriteria(policy)).distinctBy { it.id },
            tasks = normalizedPrelude + researchTasks + normalizedDeferredTools + finalTask,
        )
    }

    private fun researchRoleContracts(policy: AgentResearchPolicy): List<ResearchRoleContract> = buildList {
        add(
            ResearchRoleContract(
                id = "research_discovery",
                titlePrefix = "Discovery",
                protocol = "Model the request's real evidence landscape, terminology, ambiguities, candidate explanations, and unanswered branches without doing the later roles prematurely.",
                acceptance = "The request-specific evidence landscape and its important unresolved branches are mapped with multiple relevant non-duplicate sources.",
                minimumWeight = 1.6,
            ),
        )
        add(
            ResearchRoleContract(
                id = "research_primary",
                titlePrefix = "Primary verification",
                protocol = "Trace the material claims identified by this investigation to the strongest available first-party, original, official, measurement, dataset, standards, or source-code evidence and inspect how each source produced its result.",
                acceptance = "Material request-specific claims are checked against the strongest available primary evidence and its method and scope are understood.",
                minimumWeight = 1.9,
            ),
        )
        add(
            ResearchRoleContract(
                id = "research_contradictions",
                titlePrefix = "Adversarial review",
                protocol = "Try to make the emerging answer fail by pursuing contradictory evidence, alternate definitions, boundary cases, methodological weaknesses, selection effects, and plausible competing explanations specific to this request.",
                acceptance = "Request-specific counterevidence and answer-changing alternatives were actively sought, tested, and reconciled or kept explicit.",
                minimumWeight = 1.8,
            ),
        )
        if (policy.depth == AgentResearchDepth.DEEP) {
            add(
                ResearchRoleContract(
                    id = "research_gap_closure",
                    titlePrefix = "Gap closure",
                    protocol = "Audit the accumulated request-specific evidence model, then pursue its unresolved citations, entities, discrepancies, stale facts, weak links, and unanswered branches until each material gap is closed or honestly bounded.",
                    acceptance = "Every material gap generated by this investigation is resolved with evidence or explicitly documented as unresolved with its consequence for the answer.",
                    minimumWeight = 1.8,
                ),
            )
        }
    }

    private fun deepResearchCriteria(policy: AgentResearchPolicy): List<AgentAcceptanceCriterion> = buildList {
        add(
            AgentAcceptanceCriterion(
                id = "research_minimum_passes",
                description = "At least ${policy.minimumPasses} distinct research passes were completed before synthesis.",
                weight = 1.5,
            ),
        )
        add(
            AgentAcceptanceCriterion(
                id = "research_source_diversity",
                description = "At least ${policy.minimumDistinctSources} distinct preserved sources support the research result.",
                weight = 1.5,
            ),
        )
        if (policy.requirePrimarySourcePass) {
            add(
                AgentAcceptanceCriterion(
                    id = "research_primary_sources",
                    description = "Primary or first-party evidence was used wherever materially available.",
                    weight = 1.25,
                ),
            )
        }
        if (policy.requireContradictionPass) {
            add(
                AgentAcceptanceCriterion(
                    id = "research_contradictions",
                    description = "Contradictory evidence, limitations, and alternative explanations were actively investigated and reconciled or left explicit.",
                    weight = 1.5,
                ),
            )
        }
        if (policy.depth == AgentResearchDepth.DEEP) {
            add(
                AgentAcceptanceCriterion(
                    id = "research_gap_closure",
                    description = "Evidence gaps, stale claims, and time-sensitive facts were audited before final synthesis.",
                    weight = 1.25,
                ),
            )
        }
    }

    private fun uniqueId(preferred: String, used: MutableSet<String>): String {
        val base = preferred.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "step" }
        val id = generateSequence(base) { previous -> "${previous}_x" }.first { it !in used }
        used += id
        return id
    }

    private data class ResearchRoleContract(
        val id: String,
        val titlePrefix: String,
        val protocol: String,
        val acceptance: String,
        val minimumWeight: Double,
    )
}
