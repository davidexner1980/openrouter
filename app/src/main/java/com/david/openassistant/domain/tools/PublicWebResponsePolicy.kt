package com.david.openassistant.domain.tools

import java.net.URI
import java.util.Locale

/**
 * Recognizes only explicit HTTP throttling states and known challenge markup.
 * Broad phrases such as "automated requests" are intentionally excluded to
 * avoid treating ordinary search results as bot challenges.
 */
internal fun isPublicSearchThrottleResponse(statusCode: Int, body: String): Boolean {
    if (statusCode in setOf(202, 403, 429, 503)) return true
    val normalized = body.lowercase(Locale.US)
    return SEARCH_CHALLENGE_MARKERS.any(normalized::contains)
}

/**
 * A successful HTTP status is not enough to call a page a full-source read.
 * Consent walls, bot checks, and tiny interstitials previously entered the
 * research ledger as 37- or 57-character "successes" and falsely satisfied
 * the source-reading gate.
 */
internal fun isSubstantialPublicFetchText(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    
    // JSON and common structured formats are always considered substantial if non-empty.
    if (normalized.startsWith("{") || normalized.startsWith("[")) {
        return normalized.length > 10
    }

    if (normalized.length < MIN_PUBLIC_FETCH_CHARS) return false
    
    val wordCount = PUBLIC_FETCH_WORD_PATTERN.findAll(normalized).take(MIN_PUBLIC_FETCH_WORDS).count()
    if (wordCount < MIN_PUBLIC_FETCH_WORDS) return false

    val lower = normalized.lowercase(Locale.US)
    
    // Hard rejection for error pages and challenges
    if (WEB_ERROR_MARKERS.any(lower::contains)) return false
    
    if (normalized.length < 1500) {
        if (SEARCH_CHALLENGE_MARKERS.any(lower::contains)) return false
    } else {
        if (CRITICAL_CHALLENGE_MARKERS.any(lower::contains)) return false
    }

    return true
}

internal fun validatePublicSourceExtraction(
    extraction: PublicSourceExtraction,
    strongSubjectAnchors: List<String>,
    requiredSourceRole: String? = null
): Boolean {
    val text = extraction.sourceText
    if (!isSubstantialPublicFetchText(text)) {
        // Special case: high quality metadata pages can be shorter
        val isArXivMetadata = extraction.sourceType == "ARXIV" && 
            extraction.canonicalDocumentId != null &&
            extraction.title.isNotBlank() &&
            extraction.abstractText?.isNotBlank() == true
        
        if (!isArXivMetadata) return false
    }
    
    // Subject relevance check
    if (strongSubjectAnchors.isNotEmpty()) {
        val lowerText = text.lowercase(Locale.US)
        if (strongSubjectAnchors.none { lowerText.contains(it.lowercase(Locale.US)) }) {
            return false
        }
    }
    
    return true
}

/** Rejects PDFs before the keyless fallback reads a potentially large body. */
internal fun isPublicPdfResponse(contentType: String, url: String): Boolean {
    if (contentType.lowercase(Locale.US).contains("application/pdf")) return true
    return runCatching { URI(url).path.orEmpty().lowercase(Locale.US).endsWith(".pdf") }
        .getOrDefault(false)
}

private val CRITICAL_CHALLENGE_MARKERS = listOf(
    "captcha",
    "cf-chl-",
    "verify you are human",
    "unusual traffic",
    "bot challenge",
    "challenge-form",
    "challenge-platform",
    "anomaly-modal",
)

private val SEARCH_CHALLENGE_MARKERS = CRITICAL_CHALLENGE_MARKERS + listOf(
    "just a moment",
    "access denied",
    "enable javascript and cookies",
    "checking your browser",
    "performance & security by cloudflare",
    "ddg-captcha",
    "verification required",
    "security check",
    "pardon our interruption",
)

private val WEB_ERROR_MARKERS = listOf(
    "404 not found",
    "403 forbidden",
    "500 internal server error",
    "502 bad gateway",
    "503 service unavailable",
    "dns_probe_finished_nxdomain",
    "server not found",
    "page not found",
    "could not find the requested url",
)

private val PUBLIC_FETCH_WORD_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
private const val MIN_PUBLIC_FETCH_CHARS = 300
private const val MIN_PUBLIC_FETCH_WORDS = 35
