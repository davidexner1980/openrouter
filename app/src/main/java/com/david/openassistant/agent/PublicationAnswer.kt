package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

/**
 * Makes the published answer independently usable outside the Work screen.
 * Routed verifiers sometimes emit only internal claim markers (for example
 * `【c2】`) even though every active factual claim has an exact source URL.
 * Preserve the verifier's prose, then append any missing source links from the
 * deterministic publication graph.
 */
internal fun publicationAnswerWithSourceLinks(
    answer: String,
    claims: List<AgentClaim>,
    maximumCharacters: Int = 32_000,
    maximumSources: Int = 24,
): String {
    val normalizedAnswer = answer.trim()
    val sources = claims
        .asSequence()
        .filter { it.support !in setOf(AgentClaimSupport.UNSUPPORTED, AgentClaimSupport.CONTRADICTED) }
        .flatMap { it.sourceUrls.asSequence() }
        .map(String::trim)
        .mapNotNull(::validatedPublicationSource)
        .distinctBy(PublicationSource::url)
        .filterNot { source -> normalizedAnswer.contains(source.url) }
        .take(maximumSources.coerceAtLeast(0))
        .toList()

    val contradictions = claims.filter { it.support == AgentClaimSupport.CONTRADICTED }
    val uncertainties = claims.filter { it.type == AgentClaimType.UNCERTAINTY }

    val appendixBuilder = StringBuilder()

    // 1. Honesty: Expose unresolved contradictions
    if (contradictions.isNotEmpty()) {
        appendixBuilder.append("\n\n### Unresolved contradictions\n")
        contradictions.distinctBy { it.text.lowercase(Locale.US) }.take(5).forEach { claim ->
            appendixBuilder.append("- ${claim.text}\n")
        }
    }

    // 2. Honesty: Expose uncertainties
    if (uncertainties.isNotEmpty()) {
        appendixBuilder.append("\n\n### Uncertainties\n")
        uncertainties.distinctBy { it.text.lowercase(Locale.US) }.take(5).forEach { claim ->
            appendixBuilder.append("- ${claim.text}\n")
        }
    }

    // 3. Supporting sources
    if (sources.isNotEmpty()) {
        appendixBuilder.append("\n\n### Supporting sources")
        var appendedSourceCount = 0
        sources.forEach { source ->
            val safeUrl = source.url.replace("(", "%28").replace(")", "%29")
            val line = "- [${source.host}]($safeUrl)"
            if (appendixBuilder.length + line.length + 1 > maximumCharacters) return@forEach
            appendixBuilder.append("\n").append(line)
            appendedSourceCount += 1
        }
    }

    if (appendixBuilder.isEmpty()) {
        return normalizedAnswer.take(maximumCharacters.coerceAtLeast(0))
    }

    val appendix = appendixBuilder.toString()
    val separator = if (normalizedAnswer.isBlank()) "" else "\n\n"
    val availableAnswerCharacters =
        (maximumCharacters - separator.length - appendix.length).coerceAtLeast(0)
    
    val boundedAnswer = if (normalizedAnswer.length <= availableAnswerCharacters) {
        normalizedAnswer
    } else {
        val omission = "\n\n[Answer text shortened to preserve its verified research context.]"
        normalizedAnswer
            .take((availableAnswerCharacters - omission.length).coerceAtLeast(0))
            .trimEnd() + omission.take(availableAnswerCharacters)
    }
    
    return (boundedAnswer + separator + appendix).take(maximumCharacters)
}

private data class PublicationSource(val url: String, val host: String)

private fun validatedPublicationSource(rawUrl: String): PublicationSource? {
    if (rawUrl.isBlank() || rawUrl.length > MAX_PUBLICATION_SOURCE_URL_CHARS) return null
    if (rawUrl.any { character -> character.code < 0x20 || character.code == 0x7f }) return null
    val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
    val host = uri.host
        ?.lowercase(Locale.US)
        ?.removePrefix("www.")
        ?.takeIf(String::isNotBlank)
        ?: return null
    val normalizedUrl = uri.toASCIIString()
    return PublicationSource(normalizedUrl, host)
}

private const val MAX_PUBLICATION_SOURCE_URL_CHARS = 1_024
