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
        description = "Uses Auto Router Beta for difficult reasoning, Free Models Router for economical research work, and Body Builder internally to constructing or repairing complex requests.",
    ),
    FREE(
        displayName = "Free Models Router",
        description = "Uses the OpenRouter free router for discovery searches, source triage, and repetitive research subtasks.",
    );

    companion object {
        fun fromStoredName(name: String?): ModelProfile = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
