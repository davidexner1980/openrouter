package com.david.openassistant.agent

import java.util.UUID

enum class ResearchDraftStatus {
    DRAFT,
    READY,
    STARTING,
    STARTED,
}

enum class DurableSchedulingState {
    NOT_SCHEDULED,
    GOAL_PERSISTED,
    SCHEDULING_PENDING,
    SCHEDULING_FAILED,
    SCHEDULED,
}

data class ResearchDraft(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    /** Exact user-authored request. Never replace this with model-generated briefing text. */
    val originalUserRequest: String = "",
    val title: String = "",
    val question: String = "",
    val objective: String = "",
    val confirmedConstraints: List<String> = emptyList(),
    val inferredPreferences: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val evidenceRequirements: List<String> = emptyList(),
    val preferredSourceTypes: List<String> = emptyList(),
    val freshnessRequirement: String? = null,
    val exclusions: List<String> = emptyList(),
    val desiredDeliverable: String = "A source-traceable research result that reconciles evidence, counterevidence, methodology, and unresolved uncertainty.",
    val sourceMessageIds: List<String> = emptyList(),
    val resolvedResearchRequest: ResolvedResearchRequest? = null,
    val version: Int = 1,
    val status: ResearchDraftStatus = ResearchDraftStatus.DRAFT,
    val durableSchedulingState: DurableSchedulingState = DurableSchedulingState.NOT_SCHEDULED,
    val linkedGoalId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

object BriefingPrompts {
    const val BRIEFING_SYSTEM_PROMPT =
        "You are a research briefing specialist. Your job is to analyze a conversation between a user and an assistant and distill it into a structured Research Brief for an autonomous deep-research agent. " +
            "Identify the core research question, the user's objective, confirmed constraints, inferred preferences, and any important unresolved questions that the user should consider. " +
            "Be precise and avoid filler. Do not invent constraints the user has not mentioned, but do identify logical preferences based on the context."

    fun briefingUserPrompt(conversationHistory: String): String =
        "Analyze the following conversation and produce a structured Research Brief in JSON format.\n\n" +
            "CONVERSATION:\n$conversationHistory\n\n" +
            "Return a JSON object with the following fields:\n" +
            "- title: a short working title for the mission\n" +
            "- question: the central research question\n" +
            "- objective: the primary goal of the investigation\n" +
            "- confirmed_constraints: list of strings (explicit user requirements)\n" +
            "- inferred_preferences: list of strings (logical preferences based on context)\n" +
            "- unresolved_questions: list of strings (material unknowns or options the user might want to clarify)\n" +
            "- evidence_requirements: list of strings (types of data or proof required)\n" +
            "- preferred_source_types: list of strings (e.g., primary sources, official docs, forum discussions)\n" +
            "- freshness_requirement: string (e.g., 'last 6 months', 'current') or null\n" +
            "- exclusions: list of strings (topics or sources to avoid)\n" +
            "- desired_deliverable: a description of the final report format\n"
}
