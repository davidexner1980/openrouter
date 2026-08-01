package com.david.openassistant.agent

/**
 * A provider can stop after emitting most of a valid plan. Recover only the
 * mechanical tail of a request-specific plan: at most one missing research
 * role and a missing synthesis step. This adds no findings, sources, claims,
 * queries, or subject-matter assertions.
 */
internal data class RecoveredPlanTail(
    val plan: AgentPlanDraft,
    val addedResearchMilestones: Int,
    val addedSynthesisMilestone: Boolean,
    val reclassifiedSynthesisMilestone: Boolean,
) {
    val changed: Boolean
        get() = addedResearchMilestones > 0 ||
            addedSynthesisMilestone ||
            reclassifiedSynthesisMilestone
}

internal fun recoverNearCompletePlanTail(
    draft: AgentPlanDraft,
    policy: AgentResearchPolicy,
    exactRequest: String,
): RecoveredPlanTail? {
    val finalTask = draft.tasks.lastOrNull()
    val reclassifyFinalSynthesis = finalTask?.capability == AgentCapability.REASON &&
        draft.tasks.dropLast(1).any { it.capability == AgentCapability.DEEP_RESEARCH } &&
        SYNTHESIS_TASK_IDENTITY_PATTERN.containsMatchIn("${finalTask.id} ${finalTask.title}")
    val workingDraft = if (reclassifyFinalSynthesis) {
        draft.copy(
            tasks = draft.tasks.dropLast(1) + finalTask.copy(
                capability = AgentCapability.SYNTHESIZE,
            ),
        )
    } else {
        draft
    }
    val preservedMaterial = buildString {
        appendLine(workingDraft.title)
        appendLine(workingDraft.objective)
        appendLine(workingDraft.finalOutputDescription)
        workingDraft.acceptanceCriteria.forEach { appendLine(it.description) }
        workingDraft.tasks.forEach { task ->
            appendLine(task.title)
            appendLine(task.instructions)
            task.acceptanceCriteria.forEach { appendLine(it.description) }
        }
    }
    if (!requestSpecificMaterialAnchorsRequest(exactRequest, preservedMaterial)) return null
    if (!policy.requiresResearch) {
        return RecoveredPlanTail(
            plan = workingDraft,
            addedResearchMilestones = 0,
            addedSynthesisMilestone = false,
            reclassifiedSynthesisMilestone = reclassifyFinalSynthesis,
        )
    }

    val reasoning = workingDraft.tasks.filter { it.capability == AgentCapability.REASON }
    val research = workingDraft.tasks.filter { it.capability == AgentCapability.DEEP_RESEARCH }
    val deferredTools = workingDraft.tasks.filter {
        it.capability in setOf(AgentCapability.TOOL_USE, AgentCapability.TOOL_CREATE)
    }
    val synthesis = workingDraft.tasks.filter { it.capability == AgentCapability.SYNTHESIZE }

    if (reasoning.isEmpty() || synthesis.size > 1) return null
    if (research.size !in (policy.minimumPasses - 1)..policy.minimumPasses) return null
    if (research.any { it.instructions.length < 80 || it.acceptanceCriteria.isEmpty() }) return null
    if (research.size == policy.minimumPasses - 1) {
        val expectedPrefix = listOf(
            ResearchPassRole.DISCOVERY,
            ResearchPassRole.PRIMARY,
            ResearchPassRole.CONTRADICTION,
            ResearchPassRole.GAP_CLOSURE,
        ).take(research.size)
        if (research.map { researchPassRole(it) } != expectedPrefix) return null
    }

    val usedIds = workingDraft.tasks.mapTo(mutableSetOf()) { it.id }
    val recoveredResearch = if (research.size == policy.minimumPasses - 1) {
        listOf(
            missingResearchTail(
                roleIndex = research.size,
                draft = workingDraft,
                exactRequest = exactRequest,
                usedIds = usedIds,
            ) ?: return null,
        )
    } else {
        emptyList()
    }

    val allResearch = research + recoveredResearch
    var previousId = allResearch.lastOrNull()?.id ?: reasoning.last().id
    val normalizedTools = deferredTools.map { task ->
        task.copy(dependsOn = listOf(previousId)).also { previousId = task.id }
    }
    val recoveredSynthesis = if (synthesis.isEmpty()) {
        recoveredSynthesisTail(workingDraft, exactRequest, previousId, usedIds)
    } else {
        synthesis.single().copy(dependsOn = listOf(previousId))
    }

    return RecoveredPlanTail(
        plan = workingDraft.copy(
            tasks = reasoning + allResearch + normalizedTools + recoveredSynthesis,
        ),
        addedResearchMilestones = recoveredResearch.size,
        addedSynthesisMilestone = synthesis.isEmpty(),
        reclassifiedSynthesisMilestone = reclassifyFinalSynthesis,
    )
}

/**
 * The discovery pass must name the user's subject directly. Later passes may
 * refer to the candidates, measurements, or evidence established by earlier
 * dependencies, but any explicit role labels must remain in policy order.
 */
internal fun researchPlanMaintainsRequestContext(
    request: String,
    researchTasks: List<AgentTaskDraft>,
): Boolean {
    if (researchTasks.isEmpty()) return false
    val discovery = researchTasks.first()
    if (
        !requestSpecificMaterialAnchorsRequest(
            request = request,
            material = "${discovery.title} ${discovery.instructions}",
            minimumMatches = 2,
        )
    ) {
        return false
    }
    val supportedRoles = listOf(
        ResearchPassRole.DISCOVERY,
        ResearchPassRole.PRIMARY,
        ResearchPassRole.CONTRADICTION,
        ResearchPassRole.GAP_CLOSURE,
    )
    if (researchTasks.size > supportedRoles.size) return false
    val expectedRoles = supportedRoles.take(researchTasks.size)
    return researchTasks.map { researchPassRole(it) }.zip(expectedRoles).all { (actual, expected) ->
        actual == expected || actual == ResearchPassRole.GENERAL
    }
}

private fun missingResearchTail(
    roleIndex: Int,
    draft: AgentPlanDraft,
    exactRequest: String,
    usedIds: MutableSet<String>,
): AgentTaskDraft? {
    val role = when (roleIndex) {
        2 -> RecoveredResearchRole(
            id = "research_contradictions_recovered",
            title = "Adversarial contradiction review",
            instruction = "Challenge the emerging answer by seeking contradictory evidence, alternate definitions, boundary cases, methodological weaknesses, and plausible competing explanations.",
            criterion = "The strongest answer-changing counterevidence and alternatives are tested and reconciled or retained as explicit uncertainty.",
        )
        3 -> RecoveredResearchRole(
            id = "research_gap_closure_recovered",
            title = "Gap and freshness closure",
            instruction = "Audit the accumulated evidence for unresolved citations, entities, discrepancies, stale facts, weak links, and unanswered branches, then close or explicitly bound every material gap.",
            criterion = "Every material evidence gap or freshness risk is resolved from sources or explicitly bounded with its consequence for the final answer.",
        )
        else -> return null
    }
    val id = uniqueRecoveredId(role.id, usedIds)
    val request = exactRequest.replace(Regex("\\s+"), " ").trim().take(600)
    return AgentTaskDraft(
        id = id,
        title = "${role.title}: ${draft.title}".take(120),
        instructions = buildString {
            appendLine(role.instruction)
            appendLine("Exact request: $request")
            appendLine("Investigation objective: ${draft.objective}")
            appendLine("Required final output: ${draft.finalOutputDescription}")
            append("Derive all searches and follow-ups from the preserved request-specific plan and evidence. Do not invent findings or treat this structural recovery as completed research.")
        }.take(2_000),
        capability = AgentCapability.DEEP_RESEARCH,
        dependsOn = emptyList(),
        weight = 1.8,
        acceptanceCriteria = listOf(
            AgentAcceptanceCriterion(
                id = "${id}_request_specific_completion",
                description = "For the exact request '$request', ${role.criterion}",
                weight = 1.25,
            ),
        ),
    )
}

private fun recoveredSynthesisTail(
    draft: AgentPlanDraft,
    exactRequest: String,
    previousId: String,
    usedIds: MutableSet<String>,
): AgentTaskDraft {
    val id = uniqueRecoveredId("synthesize_recovered", usedIds)
    val request = exactRequest.replace(Regex("\\s+"), " ").trim().take(600)
    return AgentTaskDraft(
        id = id,
        title = "Synthesize verified result: ${draft.title}".take(120),
        instructions = buildString {
            appendLine("Produce the requested final output only from evidence that survives discovery, primary-source verification, contradiction review, and gap closure.")
            appendLine("Exact request: $request")
            appendLine("Investigation objective: ${draft.objective}")
            appendLine("Required final output: ${draft.finalOutputDescription}")
            append("Reconcile disagreements, distinguish fact from inference, cite preserved sources, and expose unresolved uncertainty. This recovered control step supplies no facts of its own.")
        }.take(2_000),
        capability = AgentCapability.SYNTHESIZE,
        dependsOn = listOf(previousId),
        weight = 2.5,
        acceptanceCriteria = listOf(
            AgentAcceptanceCriterion(
                id = "${id}_evidence_only",
                description = "The final response answers '$request' from preserved, verified evidence with direct citations and explicit remaining uncertainty.",
                weight = 1.5,
            ),
        ),
    )
}

private fun uniqueRecoveredId(preferred: String, usedIds: MutableSet<String>): String {
    val id = generateSequence(preferred) { previous -> "${previous}_x" }
        .first { it !in usedIds }
    usedIds += id
    return id
}

private data class RecoveredResearchRole(
    val id: String,
    val title: String,
    val instruction: String,
    val criterion: String,
)

private val SYNTHESIS_TASK_IDENTITY_PATTERN = Regex(
    "\\b(synthesis|synthesize|synthesise)\\b",
    RegexOption.IGNORE_CASE,
)
