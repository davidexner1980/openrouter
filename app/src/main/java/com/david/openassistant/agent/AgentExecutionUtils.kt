package com.david.openassistant.agent

import com.david.openassistant.agent.*
import com.david.openassistant.domain.model.AgentModelSelector
import java.net.URI

fun appendEvent(events: List<AgentEvent>, message: String): List<AgentEvent> =
    (events + AgentEvent(message = message)).takeLast(EVENT_RETENTION)

fun appendEvidence(evidence: List<AgentEvidence>, item: AgentEvidence): List<AgentEvidence> =
    evidence + item

fun upsertEvidence(evidence: List<AgentEvidence>, item: AgentEvidence): List<AgentEvidence> =
    if (evidence.any { it.id == item.id }) {
        evidence.map { existing -> if (existing.id == item.id) item else existing }
    } else {
        evidence + item
    }

fun appendCheckpoint(
    checkpoints: List<AgentCheckpoint>,
    checkpoint: AgentCheckpoint,
): List<AgentCheckpoint> = (checkpoints + checkpoint).takeLast(CHECKPOINT_RETENTION)

fun retainAttempts(attempts: List<AgentAttempt>): List<AgentAttempt> =
    attempts.takeLast(ATTEMPT_RETENTION)

fun retainEvidenceLinks(links: List<AgentEvidenceLink>): List<AgentEvidenceLink> =
    links.takeLast(EVIDENCE_LINK_RETENTION)

fun String.isFreeRoute(): Boolean =
    equals("openrouter/free", ignoreCase = true) || endsWith(":free", ignoreCase = true)

fun String.compactSummary(): String =
    replace(Regex("\\s+"), " ").trim().take(320).ifBlank { "Milestone completed without text output." }

internal fun String.sourceCitationField(maximumCharacters: Int): String =
    replace(SOURCE_CITATION_CONTROL_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maximumCharacters)

internal fun AgentSourceCitation.sanitizedForPersistence(
    maximumTitleCharacters: Int = SOURCE_CITATION_TITLE_LIMIT,
    maximumUrlCharacters: Int = SOURCE_CITATION_URL_LIMIT,
    maximumExcerptCharacters: Int = SOURCE_CITATION_EXCERPT_LIMIT,
): AgentSourceCitation {
    val cleanUrl = url.trim().take(maximumUrlCharacters)
    val cleanTitle = title.sourceCitationField(maximumTitleCharacters)
        .ifBlank { cleanUrl }
    val cleanExcerpt = excerpt
        ?.sourceCitationField(maximumExcerptCharacters)
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    return copy(title = cleanTitle, url = cleanUrl, excerpt = cleanExcerpt)
}

internal fun List<AgentSourceCitation>.sanitizedForPersistence(): List<AgentSourceCitation> =
    map { it.sanitizedForPersistence() }

fun Double.asPercent(): String = "${(coerceIn(0.0, 1.0) * 100).toInt()}%"

fun String.normalizedClaimText(): String =
    lowercase().replace(Regex("\\s+"), " ").trim()

const val MAX_EVIDENCE_CONTENT_CHARS = 16_000
const val ATTEMPT_RETENTION = 1_000
const val EVENT_RETENTION = 1_000
const val CHECKPOINT_RETENTION = 500
const val EVIDENCE_LINK_RETENTION = 5_000
const val MIN_STEP_COMPLETION_SCORE = 0.68
const val MIN_PROGRESS_DELTA = 0.01
private val SOURCE_CITATION_CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
private const val SOURCE_CITATION_TITLE_LIMIT = 240
private const val SOURCE_CITATION_URL_LIMIT = 2_048
private const val SOURCE_CITATION_EXCERPT_LIMIT = 2_400

fun uniqueTaskId(preferred: String, existingIds: Set<String>): String {
    val base = preferred
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(64)
        .ifBlank { "correction" }
    return generateSequence(base) { current -> "${current}_x" }
        .first { candidate -> candidate !in existingIds }
}
