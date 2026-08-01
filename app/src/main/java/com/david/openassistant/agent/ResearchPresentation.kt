package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

data class ResearchActivitySummary(
    val searches: Int,
    val fetches: Int,
    val uniqueSources: Int,
    val rabbitHoleBranches: Int,
)

/**
 * Reconstructs user-facing research activity from durable mission records.
 *
 * Provider summaries are not trusted as the only source because older runs did
 * not always populate every counter. Runtime-owned tool audit entries and
 * canonical source records provide a second, inspectable source of truth.
 */
internal fun AgentGoal.researchActivitySummary(): ResearchActivitySummary {
    val attemptSearches = attempts.sumOf { it.webSearchRequests ?: 0 }
    val attemptFetches = attempts.sumOf { it.webFetchRequests ?: 0 }
    val attemptBranches = attempts.sumOf { it.rabbitHoleIterations ?: 0 }

    val audit = recoverResearchToolAudit(evidence)
    val auditSearches = audit.count { it.succeeded && it.toolName == "public_web_search" }
    val auditFetches = audit.count { it.succeeded && it.toolName == "public_web_fetch" }
    val auditBranchIds = audit.asSequence()
        .mapNotNull { execution ->
            RABBIT_HOLE_BRANCH.find(execution.summary)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        .toSet()

    val canonicalSources = buildSet {
        sourceReads.forEach { read ->
            canonicalPresentationUrl(read.canonicalUrl)?.let(::add)
        }
        evidence.asSequence().flatMap { it.sources.asSequence() }.forEach { source ->
            canonicalPresentationUrl(source.url)?.let(::add)
        }
        claims.asSequence().flatMap { it.sourceUrls.asSequence() }.forEach { url ->
            canonicalPresentationUrl(url)?.let(::add)
        }
    }

    return ResearchActivitySummary(
        searches = maxOf(attemptSearches, auditSearches),
        fetches = maxOf(attemptFetches, auditFetches),
        uniqueSources = canonicalSources.size,
        rabbitHoleBranches = maxOf(attemptBranches, auditBranchIds.size),
    )
}

internal fun canonicalPresentationUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching null
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching null
        val port = when {
            uri.port == -1 -> -1
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        val retainedQuery = uri.rawQuery
            ?.split('&')
            ?.filterNot { parameter ->
                val key = parameter.substringBefore('=').lowercase(Locale.US)
                key.startsWith("utm_") || key in TRACKING_QUERY_KEYS
            }
            ?.joinToString("&")
            ?.takeIf(String::isNotBlank)
        val path = uri.rawPath?.ifBlank { "/" } ?: "/"
        URI(scheme, null, host, port, path, retainedQuery, null).toString()
    }.getOrNull()
}

internal fun descriptiveSourceLabel(source: AgentSourceCitation): String {
    val cleanedTitle = source.title
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("source", ignoreCase = true) }
    val host = runCatching { URI(source.url).host?.removePrefix("www.") }.getOrNull()
        ?.takeIf(String::isNotBlank)
    return when {
        cleanedTitle != null && cleanedTitle.length <= MAX_SOURCE_LABEL_CHARS -> cleanedTitle
        cleanedTitle != null && host != null -> "${cleanedTitle.take(34).trimEnd()}… · $host"
        cleanedTitle != null -> "${cleanedTitle.take(MAX_SOURCE_LABEL_CHARS - 1).trimEnd()}…"
        host != null -> host
        else -> "Source"
    }
}

internal fun descriptiveUrlLabel(url: String): String {
    val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
        ?.takeIf(String::isNotBlank)
    val pathLabel = runCatching {
        URI(url).path
            ?.trim('/')
            ?.substringAfterLast('/')
            ?.replace('-', ' ')
            ?.replace('_', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.length in 4..42 }
    }.getOrNull()
    return when {
        pathLabel != null && host != null -> "${pathLabel.take(32)} · $host"
        host != null -> host
        else -> "Web source"
    }
}

internal fun sourceRoleLabel(url: String): String {
    val host = runCatching { URI(url).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
    return when {
        host.endsWith("ngs.noaa.gov") || host.endsWith("noaa.gov") -> "Official geodetic record"
        host.endsWith("usgs.gov") || host.endsWith("geonames.usgs.gov") || host.endsWith("nationalmap.gov") -> "Official government source"
        host.endsWith("nps.gov") -> "Official agency source"
        host.endsWith(".gov") || host.contains(".gov.") -> "Official public record"
        host.endsWith(".edu") || host.contains(".edu.") -> "Academic source"
        host.endsWith("wikipedia.org") -> "Reference summary"
        host.isNotBlank() -> "Secondary web source"
        else -> "Source"
    }
}

internal fun confidenceExplanation(
    claim: AgentClaim,
    evidenceById: Map<String, AgentEvidence>,
): List<String> = buildList {
    add("Confidence: ${(claim.confidence.coerceIn(0.0, 1.0) * 100).toInt()}%")
    add("Review status: ${claim.support.name.lowercase(Locale.US).replace('_', ' ')}")

    val linkedEvidence = claim.supportingEvidenceIds.mapNotNull(evidenceById::get)
    if (linkedEvidence.isNotEmpty()) {
        add("${linkedEvidence.size} durable evidence record${if (linkedEvidence.size == 1) "" else "s"} linked")
    } else {
        add("No durable evidence record is linked")
    }

    val canonicalUrls = (
        claim.sourceUrls + linkedEvidence.flatMap { evidence -> evidence.sources.map { it.url } }
    ).mapNotNull(::canonicalPresentationUrl).distinct()
    if (canonicalUrls.isNotEmpty()) {
        val roles = canonicalUrls.map(::sourceRoleLabel).distinct()
        add("${canonicalUrls.size} canonical web source${if (canonicalUrls.size == 1) "" else "s"}: ${roles.joinToString()}")
    } else {
        add("No direct web source is linked")
    }

    claim.reviewExplanation?.trim()?.takeIf(String::isNotBlank)?.let { explanation ->
        add("Independent review: ${explanation.take(400)}")
    }
}

private val RABBIT_HOLE_BRANCH = Regex("Rabbit-hole (?:branch|search|read) \\[(\\d+)]", RegexOption.IGNORE_CASE)
private val TRACKING_QUERY_KEYS = setOf("fbclid", "gclid", "mc_cid", "mc_eid", "ref", "source")
private const val MAX_SOURCE_LABEL_CHARS = 52
