package com.david.openassistant.agent

import java.time.LocalDate

/** Supplies a deterministic clock boundary to models whose training date may be stale. */
internal fun providerTemporalContext(today: LocalDate = LocalDate.now()): String =
    "Current device date: $today. Resolve current, latest, today, recent, price, availability, and freshness relative to this date. " +
        "Do not cap current research at an older training year; verify time-sensitive claims from dated sources."

/**
 * Rejects only an explicit outdated upper boundary for a freshness-sensitive
 * request. Historical requests that name their own year remain untouched.
 */
internal fun planTemporalScopeIsCurrent(
    request: String,
    material: String,
    today: LocalDate = LocalDate.now(),
): Boolean {
    if (!FRESHNESS_SENSITIVE_REQUEST_PATTERN.containsMatchIn(request)) return true
    if (
        EXPLICIT_YEAR_PATTERN.containsMatchIn(request) &&
        !CURRENT_RELATIVE_REQUEST_PATTERN.containsMatchIn(request)
    ) {
        return true
    }

    val normalized = material.replace('\n', ' ')
    val boundedYears = buildList {
        STALE_SCOPE_PHRASE_PATTERN.findAll(normalized).forEach { match ->
            EXPLICIT_YEAR_PATTERN.findAll(match.value)
                .mapNotNull { it.value.toIntOrNull() }
                .maxOrNull()
                ?.let(::add)
        }
    }
    return boundedYears.none { upperYear -> upperYear < today.year }
}

private val FRESHNESS_SENSITIVE_REQUEST_PATTERN = Regex(
    "\\b(current|latest|today|recent|newest|best|recommend|price|cost|available|availability|buy|market)\\b",
    RegexOption.IGNORE_CASE,
)

private val CURRENT_RELATIVE_REQUEST_PATTERN = Regex(
    "\\b(current|currently|latest|today|recent|newest|now|available|availability)\\b",
    RegexOption.IGNORE_CASE,
)

private val EXPLICIT_YEAR_PATTERN = Regex("\\b(?:19|20)\\d{2}\\b")

private val STALE_SCOPE_PHRASE_PATTERN = Regex(
    "\\b(?:as of|through|up to|until|latest available quarter|currently marketed|currently for sale|market overview|candidate list)\\b[^.!?]{0,120}",
    RegexOption.IGNORE_CASE,
)
