package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

/**
 * Keeps claim-level citations precise. A claim may inherit a URL from a
 * referenced evidence item only when that evidence has exactly one distinct
 * source; a multi-source bundle is a context link, not proof that every URL
 * supports every claim.
 */
internal fun resolvePreciseClaimSourceUrls(
    explicitSourceUrls: List<String>,
    referencedEvidenceIds: List<String>,
    evidence: List<AgentEvidence>,
): List<String> = buildList {
    addAll(explicitSourceUrls.map(String::trim).filter(String::isNotBlank))
    referencedEvidenceIds.distinct().forEach { evidenceId ->
        evidence.firstOrNull { it.id == evidenceId }
            ?.sources
            ?.map { it.url.trim() }
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.singleOrNull()
            ?.let(::add)
    }
}.distinct()

/** Repairs source fan-out created by older releases without guessing support. */
internal fun repairOverAttributedClaim(
    claim: AgentClaim,
    evidence: List<AgentEvidence>,
    maximumDirectSources: Int = 8,
): AgentClaim {
    if (claim.sourceUrls.size <= maximumDirectSources) return claim
    val referencedEvidence = evidence.filter { it.id in claim.supportingEvidenceIds }
    val bundledUrls = referencedEvidence
        .flatMap { item -> item.sources.map { it.url } }
        .toSet()
    if (bundledUrls.size <= maximumDirectSources) return claim

    val directlyTraceable = claim.sourceUrls.filter { url ->
        url !in bundledUrls || claim.text.contains(url, ignoreCase = true)
    }
    val singleSourceInferences = resolvePreciseClaimSourceUrls(
        explicitSourceUrls = emptyList(),
        referencedEvidenceIds = claim.supportingEvidenceIds,
        evidence = evidence,
    )
    val repairedUrls = (directlyTraceable + singleSourceInferences).distinct()
    val repairedSupport = when {
        claim.support == AgentClaimSupport.CONTRADICTED -> claim.support
        claim.type == AgentClaimType.FACT && repairedUrls.isEmpty() -> AgentClaimSupport.PARTIAL
        claim.type == AgentClaimType.FACT -> AgentClaimSupport.SUPPORTED
        else -> claim.support
    }
    return claim.copy(sourceUrls = repairedUrls, support = repairedSupport)
}

internal data class ImpreciseClaimSourceSelection(
    val claimId: String,
    val citedUrl: String,
    val betterMatchingUrl: String,
)

/**
 * Detects a narrow but costly attribution error: citing one product/entity page
 * while a different page from the same host and referenced evidence bundle is
 * a materially stronger identity match for the factual claim. The two-token,
 * two-point margin deliberately avoids guessing from weak topical overlap.
 */
internal fun findImpreciseClaimSourceSelections(
    claims: List<AgentClaim>,
    evidence: List<AgentEvidence>,
): List<ImpreciseClaimSourceSelection> = buildList {
    claims.asSequence()
        .filter { it.type == AgentClaimType.FACT }
        .forEach { claim ->
            val claimTokens = claimIdentityTokens(claim.text)
            if (claimTokens.size < MIN_SOURCE_IDENTITY_MATCHES) return@forEach
            val referencedBundles = evidence.filter { it.id in claim.supportingEvidenceIds }
            claim.sourceUrls.distinct().forEach citedUrlLoop@{ citedUrl ->
                val citedKey = comparableSourceUrl(citedUrl) ?: return@citedUrlLoop
                val citedHost = sourceHost(citedUrl) ?: return@citedUrlLoop
                val candidateSources = referencedBundles
                    .asSequence()
                    .flatMap { it.sources.asSequence() }
                    .filter { sourceHost(it.url) == citedHost }
                    .distinctBy { comparableSourceUrl(it.url) }
                    .toList()
                val citedSource = candidateSources.firstOrNull {
                    comparableSourceUrl(it.url) == citedKey
                } ?: return@citedUrlLoop
                val citedScore = sourceIdentityScore(claimTokens, citedSource)
                val better = candidateSources
                    .asSequence()
                    .filterNot { comparableSourceUrl(it.url) == citedKey }
                    .map { source -> source to sourceIdentityScore(claimTokens, source) }
                    .filter { (_, score) -> score >= MIN_SOURCE_IDENTITY_MATCHES }
                    .maxByOrNull { (_, score) -> score }
                    ?.takeIf { (_, score) -> score >= citedScore + MIN_SOURCE_IDENTITY_MARGIN }
                    ?.first
                    ?: return@citedUrlLoop
                add(
                    ImpreciseClaimSourceSelection(
                        claimId = claim.id,
                        citedUrl = citedUrl,
                        betterMatchingUrl = better.url,
                    ),
                )
            }
        }
}

/**
 * Applies only the detector's narrow, provenance-preserving correction. The
 * replacement URL already belongs to the same host and one of the claim's
 * explicitly referenced evidence bundles; no new source or support is
 * invented. This prevents a mechanically repairable page choice from
 * consuming another provider attempt or suppressing evidence-gap recovery.
 */
internal fun refineImpreciseClaimSourceSelections(
    claims: List<AgentClaim>,
    evidence: List<AgentEvidence>,
): List<AgentClaim> {
    val replacements = findImpreciseClaimSourceSelections(claims, evidence)
        .groupBy { it.claimId }
    if (replacements.isEmpty()) return claims
    return claims.map { claim ->
        val claimReplacements = replacements[claim.id].orEmpty()
        if (claimReplacements.isEmpty()) {
            claim
        } else {
            val replacementByUrl = claimReplacements.associate { it.citedUrl to it.betterMatchingUrl }
            claim.copy(
                sourceUrls = claim.sourceUrls
                    .map { sourceUrl -> replacementByUrl[sourceUrl] ?: sourceUrl }
                    .distinct(),
            )
        }
    }
}

private fun sourceIdentityScore(
    claimTokens: Set<String>,
    source: AgentSourceCitation,
): Int = claimTokens.count(sourceIdentityTokens(source)::contains)

private fun sourceIdentityTokens(source: AgentSourceCitation): Set<String> = buildSet {
    addAll(identityTokens(source.title))
    val uri = runCatching { URI(source.url) }.getOrNull()
    addAll(identityTokens(uri?.path.orEmpty()))
}

private fun claimIdentityTokens(text: String): Set<String> = identityTokens(text)
    .filterNotTo(linkedSetOf()) { it in CLAIM_IDENTITY_STOP_WORDS }

private fun identityTokens(value: String): Set<String> = IDENTITY_TOKEN_PATTERN
    .findAll(value.lowercase(Locale.US))
    .map { it.value }
    .filter { it.length >= 4 }
    .toSet()

private fun comparableSourceUrl(url: String): String? = runCatching {
    val uri = URI(url.trim())
    val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.") ?: return@runCatching null
    val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    "$host$path$query"
}.getOrNull()

private fun sourceHost(url: String): String? = runCatching {
    URI(url.trim()).host?.lowercase(Locale.US)?.removePrefix("www.")
}.getOrNull()?.takeIf(String::isNotBlank)

private const val MIN_SOURCE_IDENTITY_MATCHES = 2
private const val MIN_SOURCE_IDENTITY_MARGIN = 2
private val IDENTITY_TOKEN_PATTERN = Regex("[a-z0-9]+")
private val CLAIM_IDENTITY_STOP_WORDS = setOf(
    "about", "after", "against", "among", "because", "before", "being", "between",
    "could", "current", "does", "from", "have", "into", "itself", "model", "official",
    "other", "product", "should", "source", "their", "there", "these", "they", "this",
    "those", "through", "under", "using", "version", "which", "while", "with", "would",
)
