package com.david.openassistant.domain.tools

import com.david.openassistant.BuildConfig
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.network.ResearchWebNetworkConfig
import com.david.openassistant.data.network.filterSensitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private fun resolvePublicAddresses(hostname: String): List<InetAddress> {
    val asciiHost = runCatching { IDN.toASCII(hostname.trim()) }
        .getOrElse { throw UnknownHostException("The URL host is invalid.") }
    if (asciiHost.isBlank() || asciiHost.equals("localhost", true) || asciiHost.endsWith(".local", true)) {
        throw UnknownHostException("Local network hosts are not allowed.")
    }
    val addresses = runCatching { InetAddress.getAllByName(asciiHost).toList() }
        .getOrElse { throw UnknownHostException("The URL host could not be resolved.") }
    if (addresses.isEmpty() || addresses.any(::isUnsafeAddress)) {
        throw UnknownHostException("Private, local, multicast, and link-local network addresses are not allowed.")
    }
    return addresses
}

private fun isUnsafeAddress(address: InetAddress): Boolean =
    address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress

internal data class BoundedPublicResponse(
    val text: String,
    val truncated: Boolean,
)

/** Reads at most [maximumChars] plus one probe character, regardless of the declared body size. */
internal fun readBoundedPublicResponse(reader: Reader, maximumChars: Int): BoundedPublicResponse {
    require(maximumChars >= 0) { "maximumChars must not be negative." }
    val output = StringBuilder(minOf(maximumChars, 8_192))
    val buffer = CharArray(8_192)
    var exhausted = false
    while (output.length < maximumChars) {
        val read = reader.read(buffer, 0, minOf(buffer.size, maximumChars - output.length))
        when {
            read < 0 -> {
                exhausted = true
                break
            }
            read == 0 -> {
                val next = reader.read()
                if (next < 0) {
                    exhausted = true
                    break
                }
                output.append(next.toChar())
            }
            else -> output.append(buffer, 0, read)
        }
    }
    val truncated = !exhausted && reader.read() >= 0
    return BoundedPublicResponse(output.toString(), truncated)
}

private class PublicWebHttpStatusException(val statusCode: Int) :
    IllegalArgumentException("Public web request failed with HTTP $statusCode.")

class PdfUnsupportedException(message: String) : Exception(message)

data class PageSignal(
    val ruleId: String,
    val matchedStructuralElement: String,
    val confidence: Double
)

data class PublicSourceExtraction(
    val sourceType: String,
    val canonicalDocumentId: String?,
    val title: String,
    val authors: List<String> = emptyList(),
    val abstractText: String? = null,
    val publishedAt: String? = null,
    val revisedAt: String? = null,
    val doi: String? = null,
    val journalReference: String? = null,
    val categories: List<String> = emptyList(),
    val bodyText: String,
    val metadataFieldsPresent: List<String> = emptyList(),
    val sourceText: String,
    val extractionMethod: String,
    val pageSignals: List<PageSignal> = emptyList()
)

/**
 * Keyless public-web fallback used when OpenRouter paid server tools are not
 * available. It deliberately exposes only fixed HTTPS search and guarded HTTPS
 * fetch operations. It cannot access local/private network addresses.
 */
object PublicWebToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "public_web_search",
            displayName = "Public web search",
            description = "Search the public web without a separate API key. Fuses and deduplicates results from Google GBV, Reddit, Hacker News, Archive.org, SearXNG, and DuckDuckGo. Returns source URLs, titles, and snippets; site: constraints are enforced. Supports pagination via the 'page' parameter (1-3).",
            parameters = listOf(
                ToolParameter("query", "Focused web search query."),
                ToolParameter("page", "Page number to retrieve (default: 1, max: 3).", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "public_web_fetch",
            displayName = "Public web fetch",
            description = "Fetch and extract readable HTML, plain text, or JSON from one public HTTPS URL. PDFs, localhost, private networks, credentials in URLs, and unsafe redirects are rejected.",
            parameters = listOf(
                ToolParameter("url", "Public HTTPS URL to read."),
            ),
        ),
    )

    private val names = definitions.mapTo(mutableSetOf()) { it.name }
    fun handles(name: String): Boolean = name in names
}

internal enum class PublicSearchProvider(val wireName: String) {
    SEARXNG("searxng"),
    DUCKDUCKGO_LITE("duckduckgo_lite"),
    BING_RSS("bing_rss"),
    GOOGLE_GBV("google_gbv"),
    REDDIT_SEARCH("reddit_search"),
    HACKER_NEWS("hacker_news"),
    ARCHIVE_ORG("archive_org"),
    DUCKDUCKGO_HTML("duckduckgo_html"),
}

internal data class PublicSearchCandidate(
    val title: String,
    val url: String,
    val excerpt: String,
)

/** Parses Bing's keyless RSS representation without trusting or fetching any returned URL. */
internal fun parseBingRssCandidates(xml: String): List<PublicSearchCandidate> = BING_RSS_ITEM_REGEX
    .findAll(xml)
    .mapNotNull { itemMatch ->
        val item = itemMatch.groupValues[1]
        val title = rssElement(item, "title")
        val url = rssElement(item, "link")
        val excerpt = rssElement(item, "description")
        if (url.isBlank()) null else PublicSearchCandidate(title, url, excerpt)
    }
    .toList()

private fun rssElement(item: String, name: String): String = Regex(
    "<$name(?:\\s[^>]*)?>(.*?)</$name>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
).find(item)?.groupValues?.getOrNull(1)
    ?.trim()
    ?.removePrefix("<![CDATA[")
    ?.removeSuffix("]]>")
    ?.replace("&amp;", "&")
    ?.replace("&quot;", "\"")
    ?.replace("&#39;", "'")
    ?.replace("&lt;", "<")
    ?.replace("&gt;", ">")
    ?.replace(Regex("<[^>]+>"), " ")
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    .orEmpty()

private val BING_RSS_ITEM_REGEX = Regex(
    "<item(?:\\s[^>]*)?>(.*?)</item>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val SITE_CONSTRAINT_REGEX = Regex(
    "(?:^|\\s|\\()site:([A-Za-z0-9.-]+)",
    RegexOption.IGNORE_CASE,
)

internal fun extractSiteConstraints(query: String): Set<String> = SITE_CONSTRAINT_REGEX
    .findAll(query)
    .map { match -> match.groupValues[1].trim('.').lowercase(Locale.US) }
    .filter { domain ->
        domain.isNotBlank() &&
            domain.contains('.') &&
            domain.all { it.isLetterOrDigit() || it in setOf('-', '.') }
    }
    .toSet()

internal fun searchResultMatchesSiteConstraints(url: String, constraints: Set<String>): Boolean {
    if (constraints.isEmpty()) return true
    val host = runCatching { URI(url).host?.lowercase(Locale.US)?.trimEnd('.') }.getOrNull()
        ?: return false
    return constraints.any { domain -> host == domain || host.endsWith(".$domain") }
}

/** Rebuilds an already-validated HTTPS URI without re-encoding its raw query. */
internal fun rebuildValidatedPublicHttpsUrl(uri: URI, asciiHost: String): String {
    val authorityHost = if (':' in asciiHost && !asciiHost.startsWith("[")) {
        "[$asciiHost]"
    } else {
        asciiHost
    }
    val authority = if (uri.port == -1) authorityHost else "$authorityHost:${uri.port}"
    val path = uri.rawPath.orEmpty().ifBlank { "/" }
    return buildString {
        append("https://")
        append(authority)
        append(path)
        uri.rawQuery?.let { query ->
            append('?')
            append(query)
        }
    }
}

private fun buildPublicWebClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(true)
    .dns { hostname -> resolvePublicAddresses(hostname) }
    .build()

internal fun validatePublicHttpsUrl(raw: String): String {
    val uri = runCatching { URI(raw.trim()) }
        .getOrElse { throw ToolValidationException("The URL is invalid.") }
    if (!uri.scheme.equals("https", ignoreCase = true)) {
        throw ToolValidationException("Only public HTTPS URLs are allowed.")
    }
    if (uri.userInfo != null) throw ToolValidationException("Credentials in URLs are not allowed.")
    val host = uri.host?.trim()?.takeIf { it.isNotBlank() }
        ?: throw ToolValidationException("The URL has no valid host.")
    val asciiHost = runCatching { IDN.toASCII(host) }
        .getOrElse { throw ToolValidationException("The URL host is invalid.") }
    if (uri.port !in -1..65_535 || uri.port == 0) {
        throw ToolValidationException("The URL port is invalid.")
    }
    if (asciiHost.equals("localhost", true) || asciiHost.endsWith(".local", true)) {
        throw ToolValidationException("Local network hosts are not allowed.")
    }
    runCatching { resolvePublicAddresses(asciiHost) }
        .getOrElse { error -> throw ToolValidationException(error.message ?: "The URL host could not be resolved.") }
    return rebuildValidatedPublicHttpsUrl(uri, asciiHost.lowercase(Locale.US))
}

internal fun searchUrl(
    provider: PublicSearchProvider,
    query: String,
    config: ResearchWebNetworkConfig,
    page: Int = 1,
): String? {
    val encoded = URLEncoder.encode(
        query.take(MAX_QUERY_CHARS),
        StandardCharsets.UTF_8.name(),
    )
    val start = (page - 1) * 10
    return when (provider) {
        PublicSearchProvider.DUCKDUCKGO_LITE -> "https://lite.duckduckgo.com/lite/?q=$encoded"
        PublicSearchProvider.DUCKDUCKGO_HTML -> "https://html.duckduckgo.com/html/?q=$encoded"
        PublicSearchProvider.BING_RSS -> "https://www.bing.com/search?q=$encoded&format=rss"
        PublicSearchProvider.GOOGLE_GBV -> "https://www.google.com/search?q=$encoded&gbv=1&start=$start"
        PublicSearchProvider.REDDIT_SEARCH -> "https://www.google.com/search?q=$encoded+site%3Areddit.com&gbv=1"
        PublicSearchProvider.HACKER_NEWS -> "https://www.google.com/search?q=$encoded+site%3Anews.ycombinator.com&gbv=1"
        PublicSearchProvider.ARCHIVE_ORG -> "https://web.archive.org/web/*/$encoded"
        PublicSearchProvider.SEARXNG -> config.searxngBaseUrl
            ?.trimEnd('/')
            ?.let { base ->
                "$base/search?q=$encoded&format=json&categories=general&safesearch=0&pageno=$page"
            }
    }
}

internal fun htmlDecode(value: String): String = value
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace(Regex("&#(\\d+);")) { match ->
        match.groupValues[1].toIntOrNull()?.let { code -> code.toChar().toString() } ?: match.value
    }

internal fun cleanHtml(value: String): String = htmlDecode(
    value
        .replace(SCRIPT_STYLE_REGEX, " ")
        .replace(TAG_REGEX, " ")
        .replace(Regex("\\s+"), " ")
        .trim(),
)

private val SCRIPT_STYLE_REGEX = Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG_REGEX = Regex("<[^>]+>")

internal fun decodeGoogleUrl(rawHref: String): String? {
    val decoded = htmlDecode(rawHref)
    val uri = runCatching { URI(decoded) }.getOrNull() ?: return null
    val candidate = if (uri.path == "/url") {
        uri.rawQuery?.split('&')
            ?.mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2 && parts[0] == "q") {
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                } else null
            }
            ?.firstOrNull()
    } else {
        decoded
    }
    return runCatching { validatePublicHttpsUrl(candidate.orEmpty()) }.getOrNull()
}

internal fun parseGoogleGbvSources(
    html: String,
    siteConstraints: Set<String>,
): JSONArray {
    val sources = JSONArray()
    val seen = mutableSetOf<String>()
    GOOGLE_RESULT_LINK_REGEX.findAll(html).forEach { match ->
        val rawHref = match.groupValues[1]
        val href = decodeGoogleUrl(rawHref) ?: return@forEach
        if (!searchResultMatchesSiteConstraints(href, siteConstraints) || !seen.add(href)) {
            return@forEach
        }
        val title = cleanHtml(match.groupValues[2]).ifBlank { href }
        sources.put(
            JSONObject()
                .put("title", title.take(MAX_TITLE_CHARS))
                .put("url", href.take(MAX_URL_CHARS))
                .put("excerpt", ""),
        )
    }
    return sources
}

private val GOOGLE_RESULT_LINK_REGEX = Regex(
    """<a[^>]+href="/url\?q=([^&"\s]+)[^"]*"[^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private const val MAX_TITLE_CHARS = 240
private const val MAX_URL_CHARS = 2_048
private const val MAX_EXCERPT_CHARS = 900
private const val MAX_FETCH_TEXT_CHARS = 120_000
private const val MAX_RESPONSE_CHARS = 500_000
private const val MAX_REDIRECTS = 5
private const val MAX_DISCOVERED_LEADS = 15
private const val MAX_FUSED_SEARCH_RESULTS = 20
private const val MAX_QUERY_CHARS = 500

class PublicWebToolRuntime(
    private val clientOverride: OkHttpClient? = null,
    private val networkConfigProvider: () -> ResearchWebNetworkConfig = { ResearchWebNetworkConfig() },
    private val researchMonitor: ResearchMonitor? = null,
) {
    private val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    private val client by lazy { clientOverride ?: buildPublicWebClient() }
    private val searchCooldowns = ConcurrentHashMap<PublicSearchProvider, AtomicLong>()

    fun cancelActiveCalls() {
        activeCalls.toList().forEach(okhttp3.Call::cancel)
    }

    suspend fun execute(
        call: OpenRouterToolCall,
        goalId: String? = null,
        taskId: String? = null,
        blockedSources: List<com.david.openassistant.agent.BlockedSourceRecord> = emptyList()
    ): ToolExecutionResult = when (call.name) {
        "public_web_search" -> search(call.argumentsJson, goalId, taskId)
        "public_web_fetch" -> fetch(call.argumentsJson, goalId, taskId, blockedSources)
        else -> throw ToolValidationException("Unsupported public web tool: ${call.name}")
    }

    private suspend fun search(argumentsJson: String, goalId: String?, taskId: String?): ToolExecutionResult {
        val args = parseToolArguments(argumentsJson)
        val query = args.optString("query").trim()
        if (query.isBlank()) throw ToolValidationException("Missing required tool argument: query.")
        val page = args.optInt("page", 1).coerceIn(1, 3)
        val config = networkConfigProvider()
        val constraints = extractSiteConstraints(query)
        val providers = buildList {
            if (config.searxngBaseUrl != null) add(PublicSearchProvider.SEARXNG)
            add(PublicSearchProvider.GOOGLE_GBV)
            if (page == 1) {
                add(PublicSearchProvider.DUCKDUCKGO_LITE)
                add(PublicSearchProvider.BING_RSS)
                add(PublicSearchProvider.REDDIT_SEARCH)
                add(PublicSearchProvider.HACKER_NEWS)
                add(PublicSearchProvider.ARCHIVE_ORG)
            }
            add(PublicSearchProvider.DUCKDUCKGO_HTML)
        }
        val failures = mutableListOf<String>()
        val fusedSources = JSONArray()
        val seenUrls = mutableSetOf<String>()
        val successfulProviders = mutableListOf<String>()
        val networkAttempts = AtomicInteger(0)

        // Staged health-aware search instead of parallel fan-out
        for ((index, provider) in providers.withIndex()) {
            currentCoroutineContext().ensureActive()
            // Stop early if we have enough high-quality results
            if (fusedSources.length() >= 8) break 
            
            if (isSearchCoolingDown(provider)) {
                failures += "${provider.wireName} is cooling down"
                continue
            }
            
            val url = searchUrl(provider, query, config, page) ?: continue
            
            try {
                networkAttempts.incrementAndGet()
                val fetched = executeValidatedGet(url, goalId, taskId)
                if (isPublicSearchThrottleResponse(fetched.statusCode, fetched.body)) {
                    activateSearchCooldown(provider)
                    failures += "${provider.wireName} returned a throttle"
                    continue
                }
                
                val sources = when (provider) {
                    PublicSearchProvider.SEARXNG -> parseSearxngSources(fetched.body, constraints)
                    PublicSearchProvider.DUCKDUCKGO_LITE -> parseDuckDuckGoLiteSources(fetched.body, constraints)
                    PublicSearchProvider.BING_RSS -> parseBingRssSources(fetched.body, constraints)
                    PublicSearchProvider.GOOGLE_GBV -> parseGoogleGbvSources(fetched.body, constraints)
                    PublicSearchProvider.REDDIT_SEARCH -> parseRedditSources(fetched.body, constraints)
                    PublicSearchProvider.HACKER_NEWS -> parseHackerNewsSources(fetched.body, constraints)
                    PublicSearchProvider.ARCHIVE_ORG -> parseArchiveOrgSources(fetched.body, constraints)
                    PublicSearchProvider.DUCKDUCKGO_HTML -> parseDuckDuckGoSources(fetched.body, constraints)
                }
                
                if (sources.length() > 0) {
                    successfulProviders += provider.wireName
                    mergeSearchSources(fusedSources, sources, seenUrls)
                    
                    // If we have at least 5 results from one provider, we might not need more 
                    // unless it's the first provider and we want more diversity.
                    if (fusedSources.length() >= 5 && index > 0) break
                    
                    // Stagger subsequent providers if needed for diversity
                    if (fusedSources.length() < 8) delay(300)
                } else {
                    failures += "${provider.wireName} returned no parseable results"
                }
            } catch (error: PublicWebHttpStatusException) {
                if (isPublicSearchThrottleResponse(error.statusCode, "")) {
                    activateSearchCooldown(provider)
                    failures += "${provider.wireName} returned HTTP ${error.statusCode}"
                } else {
                    failures += "${provider.wireName} failed with HTTP ${error.statusCode}"
                }
            } catch (error: Exception) {
                failures += "${provider.wireName} could not be reached: ${safeSearchFailure(error)}"
            }
        }

        if (fusedSources.length() > 0) {
            val providerLabel = successfulProviders.distinct().joinToString(" + ")
            return ToolExecutionResult(
                outputJson = JSONObject()
                    .put("status", "ok")
                    .put("query", query)
                    .put("search_provider", successfulProviders.first())
                    .put("search_providers", JSONArray(successfulProviders.distinct()))
                    .put("network_attempts", networkAttempts.get())
                    .put("site_constraints", JSONArray(constraints.toList()))
                    .put("source_count", fusedSources.length())
                    .put("sources", fusedSources)
                    .toString(),
                displaySummary = "Found ${fusedSources.length()} deduplicated public web source(s) through $providerLabel for '$query'.",
                webSearchRequests = 1,
            )
        }

        val failureSummary = failures.distinct().joinToString("; ").take(1_200)
        val isThrottled = failures.any { 
            it.contains("cooling down", ignoreCase = true) || 
            it.contains("throttle", ignoreCase = true) || 
            it.contains("HTTP 429") 
        }

        if (isThrottled) {
            return ToolExecutionResult(
                outputJson = JSONObject()
                    .put("status", "throttled")
                    .put("query", query)
                    .put("message", "Every configured public search path is currently throttled or cooling down: $failureSummary")
                    .toString(),
                displaySummary = "Web search is currently throttled ($failureSummary).",
            )
        }

        throw ToolValidationException(
            "Every configured public search path was unavailable or returned no compliant results. $failureSummary"
        )
    }

    private suspend fun fetch(
        argumentsJson: String,
        goalId: String?,
        taskId: String?,
        blockedSources: List<com.david.openassistant.agent.BlockedSourceRecord> = emptyList()
    ): ToolExecutionResult {
        val args = parseToolArguments(argumentsJson)
        val requestedUrl = args.optString("url").trim()
        if (requestedUrl.isBlank()) throw ToolValidationException("Missing required tool argument: url.")
        
        val blocked = blockedSources.firstOrNull { it.canonicalUrl == requestedUrl && it.failureClass == "PDF_UNSUPPORTED" }
        if (blocked != null) {
            throw ToolValidationException("This PDF URL is already recorded as unsupported in the keyless public-web fallback. Do not retry this URL.")
        }

        val fetched = try {
            executeValidatedGet(requestedUrl, goalId, taskId)
        } catch (error: PublicWebHttpStatusException) {
            throw ToolValidationException(error.message ?: "The public web request failed.")
        }
        if (isPublicPdfResponse(fetched.contentType, fetched.finalUrl)) {
            throw PdfUnsupportedException(
                "PDF extraction is unavailable in the keyless public-web fallback. " +
                    "Do not retry this PDF URL. Search for its title or filename plus the source domain, " +
                    "then pursue an official landing page, HTML or plain-text copy, API or machine-readable " +
                    "record, accessible mirror, archive, transcript, or the PDF's cited upstream source.",
            )
        }
        val contentType = fetched.contentType.lowercase(Locale.US)
        val text = when {
            contentType.contains("text/html") || fetched.body.contains("<html", ignoreCase = true) -> cleanHtmlHeuristic(fetched.body)
            else -> fetched.body.replace("\u0000", "").trim()
        }.take(MAX_FETCH_TEXT_CHARS)
        
        val extraction = extractArXivMetadata(fetched.body, fetched.finalUrl)
            ?: PublicSourceExtraction(
                sourceType = "GENERAL_HTML",
                canonicalDocumentId = null,
                title = extractTitle(fetched.body),
                bodyText = cleanHtmlHeuristic(fetched.body),
                sourceText = text,
                extractionMethod = "HEURISTIC_CLEAN_HTML"
            )

        if (extraction.sourceText.isBlank()) throw ToolValidationException("The public URL returned no readable text.")
        
        if (!validatePublicSourceExtraction(extraction, emptyList())) {
            throw ToolValidationException(
                "The public URL returned only a short interstitial, access challenge, or otherwise " +
                    "insubstantial page, so it was not counted as a full-source read. Search for an " +
                    "official alternate page, text mirror, transcript, dataset, or upstream source.",
            )
        }

        val discoveredLeads = if (contentType.contains("text/html")) {
            extractHighQualityLeads(fetched.body, fetched.finalUrl)
        } else {
            emptyList()
        }
        val source = JSONObject()
            .put("title", extraction.title.ifBlank { fetched.finalUrl })
            .put("url", fetched.finalUrl)
            .put("excerpt", extraction.sourceText.take(MAX_EXCERPT_CHARS))
            
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("status", "ok")
                .put("url", fetched.finalUrl)
                .put("content_type", fetched.contentType)
                .put("text", extraction.sourceText)
                .put("sources", JSONArray().put(source))
                .put("discovered_leads", JSONArray(discoveredLeads))
                .put("extraction", JSONObject().apply {
                    put("source_type", extraction.sourceType)
                    put("title", extraction.title)
                    put("authors", JSONArray(extraction.authors))
                    put("abstract", extraction.abstractText ?: JSONObject.NULL)
                    put("doi", extraction.doi ?: JSONObject.NULL)
                    put("arxiv_id", extraction.canonicalDocumentId ?: JSONObject.NULL)
                })
                .toString(),
            displaySummary = "Fetched ${extraction.sourceText.length} readable characters and ${discoveredLeads.size} leads from ${URI(fetched.finalUrl).host}.",
        )
    }

    private suspend fun executeValidatedGet(initialUrl: String, goalId: String? = null, taskId: String? = null): FetchResult {
        var current = validatePublicHttpsUrl(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            val exchangeId = "public-web-${UUID.randomUUID()}"
            val startedAt = System.currentTimeMillis()
            researchMonitor?.record(
                category = "web_network",
                event = "request",
                correlationId = exchangeId,
                goalId = goalId,
                taskId = taskId,
                fields = mapOf(
                    "method" to "GET",
                    "url" to current,
                    "redirect_index" to redirectCount,
                    "safe_headers" to "Accept: text/html,text/plain,application/json,application/xhtml+xml;q=0.9,*/*;q=0.5; User-Agent: OpenAssistant-Android/${BuildConfig.VERSION_NAME}",
                ),
            )
            val request = Request.Builder()
                .url(current)
                .header("Accept", "text/html,text/plain,application/json,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("User-Agent", "OpenAssistant-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            var responseRecorded = false
            val call = client.newCall(request)
            activeCalls += call
            try {
                call.execute().use { response ->
                    if (response.isRedirect) {
                        responseRecorded = true
                        researchMonitor?.record(
                            category = "web_network",
                            event = "response",
                            correlationId = exchangeId,
                            goalId = goalId,
                            taskId = taskId,
                            fields = mapOf(
                                "http_status" to response.code,
                                "successful" to true,
                                "redirect" to true,
                                "location" to response.header("Location"),
                                "duration_ms" to (System.currentTimeMillis() - startedAt),
                                "response_headers" to response.headers.filterSensitive().toString(),
                            ),
                        )
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw ToolValidationException("The public URL exceeded the safe redirect chain.")
                        }
                        val location = response.header("Location")
                            ?: throw ToolValidationException("The public URL returned a redirect without a destination.")
                        current = validatePublicHttpsUrl(
                            response.request.url.resolve(location)?.toString().orEmpty(),
                        )
                        return@repeat
                    }
                    if (!response.isSuccessful) {
                        responseRecorded = true
                        researchMonitor?.record(
                            category = "web_network",
                            event = "response",
                            level = "ERROR",
                            correlationId = exchangeId,
                            goalId = goalId,
                            taskId = taskId,
                            fields = mapOf(
                                "http_status" to response.code,
                                "successful" to false,
                                "duration_ms" to (System.currentTimeMillis() - startedAt),
                                "response_headers" to response.headers.filterSensitive().toString(),
                            ),
                        )
                        throw PublicWebHttpStatusException(response.code)
                    }
                    val contentLength = response.body.contentLength()
                    val finalUrl = response.request.url.toString()
                    val contentType = response.header("Content-Type").orEmpty()
                    if (isPublicPdfResponse(contentType, finalUrl)) {
                        responseRecorded = true
                        researchMonitor?.record(
                            category = "web_network",
                            event = "response",
                            correlationId = exchangeId,
                            goalId = goalId,
                            taskId = taskId,
                            fields = mapOf(
                                "http_status" to response.code,
                                "successful" to true,
                                "final_url" to finalUrl,
                                "content_type" to contentType,
                                "content_length_header" to contentLength,
                                "duration_ms" to (System.currentTimeMillis() - startedAt),
                                "response_headers" to response.headers.filterSensitive().toString(),
                                "response_body_omitted" to "PDF content was rejected before its body was read.",
                            ),
                        )
                        return FetchResult(
                            finalUrl = finalUrl,
                            contentType = contentType,
                            body = "",
                            statusCode = response.code,
                        )
                    }
                    val boundedResponse = readBoundedPublicResponse(
                        reader = response.body.charStream(),
                        maximumChars = MAX_RESPONSE_CHARS,
                    )
                    responseRecorded = true
                    researchMonitor?.record(
                        category = "web_network",
                        event = "response",
                        correlationId = exchangeId,
                        goalId = goalId,
                        taskId = taskId,
                        fields = mapOf(
                            "http_status" to response.code,
                            "successful" to true,
                            "final_url" to finalUrl,
                            "content_type" to contentType,
                            "content_length_header" to contentLength,
                            "duration_ms" to (System.currentTimeMillis() - startedAt),
                            "response_headers" to response.headers.filterSensitive().toString(),
                            "response_body" to boundedResponse.text,
                            "response_characters" to boundedResponse.text.length,
                            "response_truncated" to boundedResponse.truncated,
                        ),
                    )
                    return FetchResult(
                        finalUrl = finalUrl,
                        contentType = contentType,
                        body = boundedResponse.text,
                        statusCode = response.code,
                    )
                }
            } catch (error: Throwable) {
                if (!responseRecorded) {
                    val isCancelled = (error is java.io.IOException && call.isCanceled()) || 
                        error is kotlinx.coroutines.CancellationException
                    researchMonitor?.record(
                        category = "web_network",
                        event = if (isCancelled) "cancelled" else "failure",
                        level = if (isCancelled) "INFO" else "ERROR",
                        correlationId = exchangeId,
                        goalId = goalId,
                        taskId = taskId,
                        fields = mapOf(
                            "url" to current,
                            "duration_ms" to (System.currentTimeMillis() - startedAt),
                            "error_type" to error::class.java.name,
                            "error_message" to error.message.orEmpty(),
                            "cancelled" to isCancelled
                        ),
                    )
                }
                throw error
            } finally {
                activeCalls -= call
            }
        }
        throw ToolValidationException("The public URL could not be fetched.")
    }

    private fun decodeSearchResultUrl(rawHref: String): String? {
        val decodedHref = htmlDecode(rawHref)
        val uri = runCatching { URI(decodedHref) }.getOrNull() ?: return null
        val candidate = if (uri.host?.contains("duckduckgo.com", ignoreCase = true) == true) {
            uri.rawQuery.orEmpty().split('&')
                .mapNotNull { pair ->
                    val parts = pair.split('=', limit = 2)
                    if (parts.size == 2 && parts[0] == "uddg") {
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    } else null
                }
                .firstOrNull()
        } else {
            decodedHref
        }
        return runCatching { validatePublicHttpsUrl(candidate.orEmpty()) }.getOrNull()
    }

    private fun extractArXivMetadata(html: String, url: String): PublicSourceExtraction? {
        if (!url.contains("arxiv.org")) return null
        
        val title = Regex("<h1[^>]*title mathjax[^>]*>.*?</span>(.*?)(?:</h1>|$)").find(html)?.groupValues?.get(1)?.let(::cleanHtml) ?: ""
        val abstract = Regex("<blockquote[^>]*abstract mathjax[^>]*>.*?</span>(.*?)(?:</blockquote>|$)").find(html)?.groupValues?.get(1)?.let(::cleanHtml) ?: ""
        val authors = Regex("<div[^>]*authors[^>]*>.*?</span>(.*?)(?:</div>|$)").find(html)?.groupValues?.get(1)?.let(::cleanHtml)?.split(",")?.map { it.trim() } ?: emptyList()
        val arxivId = Regex("arxiv:([0-9.]+)").find(url.lowercase(Locale.US))?.groupValues?.get(1) ?: ""

        if (title.isBlank() && abstract.isBlank()) return null
        
        val sourceText = "TITLE: $title\nAUTHORS: ${authors.joinToString()}\nABSTRACT: $abstract"
        return PublicSourceExtraction(
            sourceType = "ARXIV",
            canonicalDocumentId = arxivId,
            title = title,
            authors = authors,
            abstractText = abstract,
            bodyText = abstract,
            sourceText = sourceText,
            extractionMethod = "REGEX_ARXIV_METADATA"
        )
    }

    private fun extractTitle(html: String): String = TITLE_REGEX.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::cleanHtml)
        .orEmpty()

    private fun cleanHtmlHeuristic(html: String): String {
        val candidates = CONTENT_AREA_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.length >= 500 }
            .toList()

        // Choose the candidate that has the most readable text after cleaning,
        // falling back to the entire HTML if none are substantive.
        val mainContent = if (candidates.isEmpty()) {
            html
        } else {
            candidates.maxByOrNull { cleanHtml(it).length } ?: html
        }

        return cleanHtml(mainContent)
    }

    private fun extractHighQualityLeads(html: String, baseUrl: String): List<JSONObject> {
        val baseUri = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        val baseHost = baseUri.host?.lowercase(Locale.US) ?: ""
        
        val seenUrls = mutableSetOf<String>()
        val leads = mutableListOf<JSONObject>()

        // 1. Primary Anchor Extraction
        LITE_ANCHOR_REGEX.findAll(html).forEach { match ->
            val attributes = match.groupValues[1]
            val rawHref = HREF_ATTRIBUTE_REGEX.find(attributes)?.groupValues?.getOrNull(2) ?: return@forEach
            val absoluteUrl = runCatching { baseUri.resolve(rawHref).toString() }.getOrNull() ?: return@forEach
            
            if (!absoluteUrl.startsWith("https://", ignoreCase = true)) return@forEach
            if (!seenUrls.add(absoluteUrl)) return@forEach
            
            val candidateUri = runCatching { URI(absoluteUrl) }.getOrNull() ?: return@forEach
            val candidateHost = candidateUri.host?.lowercase(Locale.US) ?: ""
            
            if (candidateHost.isBlank() || candidateHost == baseHost || isLowQualityDiscoveryDomain(candidateHost)) {
                return@forEach
            }
            
            val title = cleanHtml(match.groupValues[2]).trim()
            if (title.length < 5 || title.length > 200) return@forEach
            
            val score = scoreLead(title, absoluteUrl, html)
            leads.add(
                JSONObject()
                    .put("title", title)
                    .put("url", absoluteUrl)
                    .put("score", score)
            )
        }

        // 2. DOI and Citation Extraction (Hidden Links)
        CITATION_REGEX.findAll(html).forEach { match ->
            val url = match.value
            if (seenUrls.add(url)) {
                leads.add(
                    JSONObject()
                        .put("title", "Document Citation / DOI")
                        .put("url", url)
                        .put("score", 50) // High importance for research citations
                )
            }
        }

        return leads
            .sortedByDescending { it.optInt("score", 0) }
            .take(MAX_DISCOVERED_LEADS)
    }

    private fun scoreLead(title: String, url: String, contextHtml: String): Int {
        var score = 0
        val text = "$title $url".lowercase(Locale.US)
        if (text.contains("pdf")) score += 60
        if (text.contains(".gov") || text.contains(".mil")) score += 55
        if (text.contains(".edu")) score += 45
        if (text.contains("doc") || text.contains("guide") || text.contains("manual") || text.contains("spec") || text.contains("api")) score += 40
        if (text.contains("archive") || text.contains("history") || text.contains("wayback") || text.contains("library")) score += 35
        if (text.contains("forum") || text.contains("thread") || text.contains("topic") || text.contains("reddit") || text.contains("stackoverflow") || text.contains("github")) score += 30
        if (text.contains("official") || text.contains("government") || text.contains(".org") || text.contains("reference")) score += 35
        if (text.contains("source") || text.contains("raw") || text.contains("dataset")) score += 30
        
        // Forensics markers (Community verification signals)
        if (contextHtml.contains("accepted", ignoreCase = true) || contextHtml.contains("solution", ignoreCase = true) || contextHtml.contains("resolved", ignoreCase = true)) score += 25
        if (contextHtml.contains("verified", ignoreCase = true) || contextHtml.contains("correct", ignoreCase = true) || contextHtml.contains("fixed", ignoreCase = true)) score += 25
        if (contextHtml.contains("best answer", ignoreCase = true) || contextHtml.contains("upvote", ignoreCase = true)) score += 20
        
        return score
    }

    private fun isLowQualityDiscoveryDomain(host: String): Boolean = host.contains("facebook.com") ||
        host.contains("twitter.com") || host.contains("x.com") || host.contains("linkedin.com") ||
        host.contains("instagram.com") || host.contains("pinterest.com") || host.contains("doubleclick.net") ||
        host.contains("googleads") || host.contains("amazon.com/adsystem") || host.contains("youtube.com/watch")

    private fun parseRedditSources(html: String, constraints: Set<String>) =
        parseGoogleGbvSources(html, constraints)

    private fun parseHackerNewsSources(html: String, constraints: Set<String>) =
        parseGoogleGbvSources(html, constraints)

    private fun parseArchiveOrgSources(html: String, constraints: Set<String>): JSONArray {
        val sources = JSONArray()
        val seen = mutableSetOf<String>()
        val archiveLinkRegex = Regex("""/web/\d+/(https?://[^\s"<>]+)""", RegexOption.IGNORE_CASE)
        archiveLinkRegex.findAll(html).forEach { match ->
            val originalUrl = match.groupValues[1]
            if (seen.add(originalUrl)) {
                sources.put(
                    JSONObject()
                        .put("title", "Wayback Machine: $originalUrl")
                        .put("url", "https://web.archive.org${match.value}")
                        .put("excerpt", "Archived version found.")
                )
            }
        }
        return sources
    }

    private fun parseDuckDuckGoSources(
        html: String,
        siteConstraints: Set<String>,
    ): JSONArray {
        val links = RESULT_LINK_REGEX.findAll(html).toList()
        val snippets = RESULT_SNIPPET_REGEX.findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .toList()
        val sources = JSONArray()
        val seen = mutableSetOf<String>()
        links.forEachIndexed { index, match ->
            val href = decodeSearchResultUrl(match.groupValues[1])
                ?: return@forEachIndexed
            if (
                !href.startsWith("https://") ||
                !searchResultMatchesSiteConstraints(href, siteConstraints) ||
                !seen.add(href)
            ) {
                return@forEachIndexed
            }
            val title = cleanHtml(match.groupValues[2]).ifBlank { href }
            sources.put(
                JSONObject()
                    .put("title", title.take(MAX_TITLE_CHARS))
                    .put("url", href.take(MAX_URL_CHARS))
                    .put("excerpt", snippets.getOrNull(index)?.take(MAX_EXCERPT_CHARS) ?: ""),
            )
        }
        return sources
    }

    private fun parseDuckDuckGoLiteSources(
        html: String,
        siteConstraints: Set<String>,
    ): JSONArray {
        val links = LITE_ANCHOR_REGEX.findAll(html)
            .mapNotNull { match ->
                val attributes = match.groupValues[1]
                if (!attributes.contains("result-link", ignoreCase = true)) return@mapNotNull null
                val href = HREF_ATTRIBUTE_REGEX.find(attributes)?.groupValues?.getOrNull(2)
                    ?: return@mapNotNull null
                href to match.groupValues[2]
            }
            .toList()
        val snippets = LITE_RESULT_SNIPPET_REGEX.findAll(html)
            .map { cleanHtml(it.groupValues[1]) }
            .toList()
        val sources = JSONArray()
        val seen = mutableSetOf<String>()
        links.forEachIndexed { index, (rawHref, rawTitle) ->
            val href = decodeSearchResultUrl(rawHref) ?: return@forEachIndexed
            if (!searchResultMatchesSiteConstraints(href, siteConstraints) || !seen.add(href)) {
                return@forEachIndexed
            }
            sources.put(
                JSONObject()
                    .put("title", cleanHtml(rawTitle).ifBlank { href }.take(MAX_TITLE_CHARS))
                    .put("url", href.take(MAX_URL_CHARS))
                    .put("excerpt", snippets.getOrNull(index)?.take(MAX_EXCERPT_CHARS) ?: ""),
            )
        }
        return sources
    }

    private fun parseSearxngSources(
        body: String,
        siteConstraints: Set<String>,
    ): JSONArray {
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return JSONArray()
        val sources = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val href = runCatching {
                validatePublicHttpsUrl(item.optString("url"))
            }.getOrNull() ?: continue
            if (!searchResultMatchesSiteConstraints(href, siteConstraints) || !seen.add(href)) continue
            sources.put(
                JSONObject()
                    .put(
                        "title",
                        item.optString("title").trim().ifBlank { href }.take(MAX_TITLE_CHARS),
                    )
                    .put("url", href.take(MAX_URL_CHARS))
                    .put(
                        "excerpt",
                        cleanHtml(
                            item.optString("content").ifBlank { item.optString("snippet") },
                        ).take(MAX_EXCERPT_CHARS),
                    ),
            )
        }
        return sources
    }

    private fun parseBingRssSources(
        body: String,
        siteConstraints: Set<String>,
    ): JSONArray {
        val sources = JSONArray()
        val seen = mutableSetOf<String>()
        parseBingRssCandidates(body).forEach { candidate ->
            val href = runCatching { validatePublicHttpsUrl(candidate.url) }.getOrNull()
                ?: return@forEach
            if (!searchResultMatchesSiteConstraints(href, siteConstraints) || !seen.add(href)) {
                return@forEach
            }
            sources.put(
                JSONObject()
                    .put("title", candidate.title.ifBlank { href }.take(MAX_TITLE_CHARS))
                    .put("url", href.take(MAX_URL_CHARS))
                    .put("excerpt", candidate.excerpt.take(MAX_EXCERPT_CHARS)),
            )
        }
        return sources
    }

    private fun mergeSearchSources(
        target: JSONArray,
        incoming: JSONArray,
        seenUrls: MutableSet<String>,
    ) {
        for (index in 0 until incoming.length()) {
            if (target.length() >= MAX_FUSED_SEARCH_RESULTS) return
            val source = incoming.optJSONObject(index) ?: continue
            val url = source.optString("url").trim()
            if (url.isBlank() || !seenUrls.add(url)) continue
            target.put(source)
        }
    }

    private fun isSearchCoolingDown(provider: PublicSearchProvider): Boolean =
        System.currentTimeMillis() < (searchCooldowns[provider]?.get() ?: 0L)

    private fun activateSearchCooldown(provider: PublicSearchProvider) {
        val jitter = Random.nextLong(-5000, 5000)
        val until = System.currentTimeMillis() + SEARCH_THROTTLE_COOLDOWN_MILLIS + jitter
        searchCooldowns.computeIfAbsent(provider) { AtomicLong(0L) }
            .updateAndGet { existing -> maxOf(existing, until) }
    }

    private fun safeSearchFailure(error: Throwable): String = when (error) {
        is UnknownHostException -> "host resolution failed"
        is java.net.ConnectException -> "connection failed"
        is java.net.SocketTimeoutException,
        is java.io.InterruptedIOException,
            -> "request timed out"
        is ToolValidationException -> error.message.orEmpty().take(240)
        else -> error::class.java.simpleName.ifBlank { "network failure" }
    }

    private data class FetchResult(
        val finalUrl: String,
        val contentType: String,
        val body: String,
        val statusCode: Int,
    )

    private companion object {
        const val SEARCH_THROTTLE_COOLDOWN_MILLIS = 60_000L
        val CONTENT_AREA_REGEX = Regex(
            "<(?:main|article|div)\\b[^>]*(?:id|class)\\s*=\\s*['\\\"][^'\\\"]*(?:content|main|article|body|post-text|entry-content)[^'\\\"]*['\\\"][^>]*>(.*?)</(?:main|article|div)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val RESULT_LINK_REGEX = Regex(
            "<a[^>]+class=\\\"[^\\\"]*result__a[^\\\"]*\\\"[^>]+href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val RESULT_SNIPPET_REGEX = Regex(
            "<(?:a|div)[^>]+class=\\\"[^\\\"]*result__snippet[^\\\"]*\\\"[^>]*>(.*?)</(?:a|div)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val LITE_ANCHOR_REGEX = Regex(
            "<a\\b([^>]*)>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val HREF_ATTRIBUTE_REGEX = Regex(
            "href\\s*=\\s*(['\\\"])(.*?)\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val LITE_RESULT_SNIPPET_REGEX = Regex(
            "<td[^>]+class\\s*=\\s*['\\\"][^'\\\"]*result-snippet[^'\\\"]*['\\\"][^>]*>(.*?)</td>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val CITATION_REGEX = Regex("""https?://(?:dx\.)?doi\.org/[^\s<>"]+|https?://arxiv\.org/pdf/[^\s<>"]+""", RegexOption.IGNORE_CASE)
    }
}
