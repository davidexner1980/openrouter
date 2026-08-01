package com.david.openassistant.domain.model

enum class ModelProfile(
    val displayName: String,
    val description: String,
) {
    MANUAL(
        displayName = "Manual",
        description = "Use the exact model selected from the catalog.",
    ),
    AUTO(
        displayName = "Auto Router Beta",
        description = "Uses Auto Router Beta for difficult reasoning, Free Models Router for economical research work, and Body Builder only to construct or repair complex OpenRouter requests.",
    ),
    FREE(
        displayName = "Free Models Router",
        description = "Uses the OpenRouter free router for discovery searches, source triage, and repetitive research subtasks.",
    ),
    BODY_BUILDER(
        displayName = "Body Builder",
        description = "Request-construction utility for designing complex multi-step request plans or diagnosing incompatible structures.",
    ),
    FAST(
        displayName = "Fast",
        description = "Use catalog-name and price heuristics for a smaller, lower-cost model. This is not measured latency.",
    ),
    DEEP(
        displayName = "Deep",
        description = "Prefer models that advertise reasoning or deeper analysis capabilities.",
    ),
    CODING(
        displayName = "Coding",
        description = "Prefer models whose catalog metadata emphasizes programming and software work.",
    ),
    VISION(
        displayName = "Vision",
        description = "Prefer a model that accepts image input for attached-image conversations.",
    );

    companion object {
        fun fromStoredName(value: String?): ModelProfile =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}
