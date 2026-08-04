package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

enum class SourceReadRejectionReason {
    HTTP_ERROR_PAGE,
    SEMANTIC_404,
    CONTENT_TOO_SHORT,
    BOILERPLATE_ONLY,
    ENTITY_MISMATCH,
    GENERIC_LANDING_PAGE,
    NO_EVIDENCE_CANDIDATE,
    WRONG_SOURCE_ROLE,
    ACCESS_CHALLENGE,
    SCRIPT_ONLY_PAGE,
    EMPTY_HTTP_200,
    UNSUPPORTED_REDIRECT,
}

data class SourceReadValidationResult(
    val isValid: Boolean,
    val rejectionReason: SourceReadRejectionReason? = null,
    val authorityScore: Int = 0,
)

/**
 * Validates a full-source read based on content, role, and domain authority.
 */
fun validateSourceRead(
    url: String,
    httpCode: Int,
    content: String,
    contentType: String = "text/html",
    requiredRole: String? = null,
    targetEntities: List<String> = emptyList(),
    headers: Map<String, String> = emptyMap(),
): SourceReadValidationResult {
    if (httpCode !in 200..299) {
        if (httpCode == 403) return SourceReadValidationResult(false, SourceReadRejectionReason.ACCESS_CHALLENGE)
        return SourceReadValidationResult(false, SourceReadRejectionReason.HTTP_ERROR_PAGE)
    }

    val trimmedContent = content.trim()
    val lowerContent = trimmedContent.lowercase(Locale.US)

    // Cloudflare / Interstitial detection
    val isChallenge = headers.containsKey("cf-mitigated") ||
        lowerContent.contains("cloudflare") ||
        lowerContent.contains("challenge-platform") ||
        lowerContent.contains("captcha") ||
        lowerContent.contains("hcaptcha") ||
        lowerContent.contains("verify you are human") ||
        lowerContent.contains("checking your browser")

    if (isChallenge) {
        return SourceReadValidationResult(false, SourceReadRejectionReason.ACCESS_CHALLENGE)
    }

    if (lowerContent.contains("404 not found") ||
        lowerContent.contains("page not found") ||
        lowerContent.contains("error 404") ||
        lowerContent.contains("404 page")
    ) {
        return SourceReadValidationResult(false, SourceReadRejectionReason.SEMANTIC_404)
    }

    val isJsonOrXml = contentType.contains("json", ignoreCase = true) ||
        contentType.contains("xml", ignoreCase = true) ||
        trimmedContent.startsWith("{") ||
        trimmedContent.startsWith("<")

    val minChars = if (isJsonOrXml) 30 else 100
    if (trimmedContent.length < minChars) {
        return SourceReadValidationResult(false, SourceReadRejectionReason.CONTENT_TOO_SHORT)
    }

    if (lowerContent.contains("enable javascript") && lowerContent.length < 500) {
        return SourceReadValidationResult(false, SourceReadRejectionReason.BOILERPLATE_ONLY)
    }

    val authorityScore = computeSourceAuthorityScore(url, lowerContent)

    if (requiredRole != null && requiredRole.equals("PRIMARY_SOURCE", ignoreCase = true) && authorityScore < 50) {
        return SourceReadValidationResult(false, SourceReadRejectionReason.WRONG_SOURCE_ROLE, authorityScore)
    }

    if (targetEntities.isNotEmpty()) {
        val matchesAnyEntity = targetEntities.any { entity ->
            lowerContent.contains(entity.lowercase(Locale.US))
        }
        if (!matchesAnyEntity) {
            return SourceReadValidationResult(false, SourceReadRejectionReason.ENTITY_MISMATCH, authorityScore)
        }
    }

    return SourceReadValidationResult(true, null, authorityScore)
}

internal fun recoverHttpsSourceCitations(vararg texts: String): List<AgentSourceCitation> {
    val recovered = linkedMapOf<String, AgentSourceCitation>()

    texts.forEach { text ->
        MARKDOWN_LINK_PATTERN.findAll(text).forEach markdownLink@{ match ->
            val url = cleanHttpsUrl(match.groupValues[2]) ?: return@markdownLink
            recovered.putIfAbsent(
                url,
                AgentSourceCitation(
                    title = match.groupValues[1].trim().ifBlank { sourceTitle(url) }.take(MAX_TITLE_CHARS),
                    url = url,
                ),
            )
        }
        HTTPS_URL_PATTERN.findAll(text).forEach bareUrl@{ match ->
            val url = cleanHttpsUrl(match.value) ?: return@bareUrl
            recovered.putIfAbsent(
                url,
                AgentSourceCitation(
                    title = sourceTitle(url),
                    url = url,
                ),
            )
        }
    }

    return recovered.values.take(MAX_RECOVERED_SOURCES).toList()
}

private fun cleanHttpsUrl(raw: String): String? {
    val candidate = raw
        .trim()
        .trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
        .take(MAX_URL_CHARS)
    if (!candidate.startsWith("https://", ignoreCase = true)) return null
    return runCatching {
        val uri = URI(candidate)
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (
            !uri.scheme.equals("https", ignoreCase = true) ||
            host.isBlank() ||
            isObviouslyPrivateHost(host) ||
            !uri.userInfo.isNullOrBlank()
        ) {
            null
        } else {
            candidate
        }
    }.getOrNull()
}

private fun isObviouslyPrivateHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if (host == "::1" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true
    val parts = host.split('.')
    val secondOctet = parts.getOrNull(1)?.toIntOrNull()
    return parts.firstOrNull() == "172" && secondOctet != null && secondOctet in 16..31
}

internal fun sourceTitle(url: String): String = runCatching {
    URI(url).host
        ?.removePrefix("www.")
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank)
}.getOrNull() ?: url.take(MAX_TITLE_CHARS)

private const val MAX_RECOVERED_SOURCES = 32
private const val MAX_TITLE_CHARS = 240
private const val MAX_URL_CHARS = 2_048
private val MARKDOWN_LINK_PATTERN = Regex("""\[([^\]\r\n]{1,240})]\s*\((https://[^\s)]+)\)""", RegexOption.IGNORE_CASE)
private val HTTPS_URL_PATTERN = Regex("""https://[^\s<>\"']+""", RegexOption.IGNORE_CASE)
