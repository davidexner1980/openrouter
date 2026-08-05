package com.david.openassistant.agent

import com.david.openassistant.BuildConfig
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.diagnostics.redactDiagnosticText
import android.util.Log
import com.david.openassistant.agent.AgentRoutingPolicy
import com.david.openassistant.agent.AgentRoutingStage
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.redactResearchMonitorText
import com.david.openassistant.data.network.filterSensitive
import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterFailureClass
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.data.openrouter.OpenRouterProtocolUtils
import com.david.openassistant.data.openrouter.SecretRedactor
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import com.david.openassistant.domain.tools.OpenRouterToolCall
import com.david.openassistant.domain.tools.SafeToolDefinition
import com.david.openassistant.domain.tools.ToolExecutionResult
import com.david.openassistant.domain.tools.ToolValidationException
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { (it.isNotBlank()) && (it != "null") }

internal fun JSONObject?.optIntOrNull(name: String): Int? =
    if (this == null || !has(name) || isNull(name)) null else optInt(name)

internal fun JSONObject?.optDoubleOrNull(name: String): Double? =
    if (this == null || !has(name) || isNull(name)) null else optDouble(name)

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

/**
 * Clones a request object before a compatibility candidate mutates it.
 * Taking the payload as an explicit argument prevents nested receiver lambdas
 * such as buildList from accidentally serializing themselves as `[]`.
 */
internal fun cloneResearchRequestPayload(payload: JSONObject): JSONObject =
    JSONObject(payload.toString())

internal fun requiredFunctionToolChoice(functionName: String): JSONObject {
    require(FUNCTION_NAME_PATTERN.matches(functionName)) { "Invalid required function name." }
    return JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", functionName))
}

internal fun relaxRequiredFunctionToolChoice(payload: JSONObject) {
    payload.remove("tool_choice")
}

internal fun finalToolFreeCompletionPayload(payload: JSONObject): JSONObject =
    JSONObject(payload.toString()).apply {
        remove("tool_choice")
        optJSONArray("messages")?.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    "The local-tool window for this round is complete. Reuse the tool results and preserved evidence above to return the required structured milestone result. Stop using tools when the result is sufficiently grounded.",
                ),
        )
    }

internal fun providerWebSearchRequestCount(usage: JSONObject?): Int {
    if (usage == null) return 0
    val legacy = usage.optJSONObject("server_tool_use")
        ?.optInt("web_search_requests", 0)
        ?: 0
    val current = usage.optJSONObject("server_tool_use_details")
        ?.optInt("web_search_requests", 0)
        ?: 0
    return maxOf(legacy, current).coerceAtLeast(0)
}

internal fun isSubstantialProviderExtract(excerpt: String?): Boolean {
    val normalized = excerpt.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (normalized.length < MIN_PROVIDER_EXTRACT_CHARS) return false
    return PROVIDER_EXTRACT_WORD_PATTERN.findAll(normalized).take(MIN_PROVIDER_EXTRACT_WORDS).count() >=
        MIN_PROVIDER_EXTRACT_WORDS
}

/** Preserves provider annotation extracts for structured-output recovery. */
internal fun providerSourceEvidenceContext(
    sources: List<AgentSourceCitation>,
    maximumChars: Int = MAX_PROVIDER_EVIDENCE_CONTEXT_CHARS,
): String {
    require(maximumChars >= 0) { "maximumChars must not be negative." }
    if (maximumChars == 0) return ""
    val output = StringBuilder(minOf(maximumChars, 8_192))
    sources.asSequence()
        .map { it.sanitizedForPersistence() }
        .filter { it.url.startsWith("https://") }
        .distinctBy { it.url }
        .forEachIndexed { index, source ->
            if (output.length >= maximumChars) return@forEachIndexed
            val entry = buildString {
                appendLine("SOURCE ${index + 1}: ${source.title.ifBlank { source.url }}")
                appendLine("URL: ${source.url}")
                source.excerpt
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { excerpt -> appendLine("RETRIEVED EXTRACT: $excerpt") }
            }.trimEnd()
            if (entry.isBlank()) return@forEachIndexed
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(entry.take(maximumChars - output.length))
        }
    return output.toString()
}

internal fun providerResearchToolExecutions(
    root: JSONObject,
    sources: List<AgentSourceCitation>,
): List<AgentToolExecution> {
    val searchRequests = providerWebSearchRequestCount(root.optJSONObject("usage"))
        .coerceAtMost(MAX_PROVIDER_RESEARCH_AUDIT_RECORDS)
    val invokedTools = providerInvokedResearchTools(root)
    val exactFetches = invokedTools.count { it == PROVIDER_WEB_FETCH_TOOL }
        .coerceAtMost(MAX_PROVIDER_RESEARCH_AUDIT_RECORDS)
    val substantialSources = sources
        .asSequence()
        .filter { source -> isSubstantialProviderExtract(source.excerpt) }
        .distinctBy { source -> source.url }
        .take(MAX_PROVIDER_RESEARCH_AUDIT_RECORDS)
        .toList()
    return buildList {
        repeat(searchRequests) { index ->
            add(
                AgentToolExecution(
                    toolName = PROVIDER_WEB_SEARCH_TOOL,
                    summary = "OpenRouter executed provider web search ${index + 1} of $searchRequests.",
                    succeeded = true,
                ),
            )
        }
        substantialSources.forEach { source ->
            add(
                AgentToolExecution(
                    toolName = PROVIDER_WEB_EXTRACT_TOOL,
                    summary = (
                        "OpenRouter supplied a substantive ${source.excerpt.orEmpty().length}-character " +
                            "query-focused source extract from ${source.url}."
                        ).take(600),
                    succeeded = true,
                ),
            )
        }
        repeat(exactFetches) { index ->
            add(
                AgentToolExecution(
                    toolName = PROVIDER_WEB_FETCH_TOOL,
                    summary = "OpenRouter router metadata recorded provider web fetch ${index + 1} of $exactFetches.",
                    succeeded = true,
                ),
            )
        }
    }
}

private fun providerInvokedResearchTools(root: JSONObject): List<String> {
    val pipeline = root.optJSONObject("openrouter_metadata")?.optJSONArray("pipeline")
        ?: return emptyList()
    return buildList {
        for (index in 0 until pipeline.length()) {
            val stage = pipeline.optJSONObject(index) ?: continue
            if (!stage.optString("type").equals("server_tools", ignoreCase = true)) continue
            addAll(providerResearchToolsFromStage(stage.optJSONObject("data") ?: stage))
        }
    }
}

private fun providerResearchToolsFromStage(data: JSONObject): List<String> {
    val candidateKeys = listOf("tools", "invoked_tools", "tool_calls", "calls")
    candidateKeys.forEach { key ->
        data.optJSONArray(key)?.let { array ->
            return buildList {
                for (index in 0 until array.length()) {
                    canonicalProviderResearchTool(array.opt(index))?.let(::add)
                }
            }
        }
        data.optJSONObject(key)?.let { counts ->
            return buildList {
                val names = counts.keys()
                while (names.hasNext()) {
                    val rawName = names.next()
                    val canonical = canonicalProviderResearchTool(rawName) ?: continue
                    val count = counts.optInt(rawName, 1)
                        .coerceIn(1, MAX_PROVIDER_RESEARCH_AUDIT_RECORDS)
                    repeat(count) { add(canonical) }
                }
            }
        }
    }
    return PROVIDER_RESEARCH_TOOL_PATTERN.findAll(data.toString())
        .map { match -> providerResearchToolName(match.groupValues[1]) }
        .distinct()
        .toList()
}

private fun canonicalProviderResearchTool(value: Any?): String? {
    val raw = when (value) {
        is JSONObject -> listOf("name", "tool_name", "type", "tool")
            .asSequence()
            .map { key -> value.optString(key) }
            .firstOrNull { candidate -> PROVIDER_RESEARCH_TOOL_PATTERN.containsMatchIn(candidate) }
            ?: value.toString()
        else -> value?.toString().orEmpty()
    }
    val match = PROVIDER_RESEARCH_TOOL_PATTERN.find(raw) ?: return null
    return providerResearchToolName(match.groupValues[1])
}

private fun providerResearchToolName(kind: String): String = when (kind.lowercase(Locale.US)) {
    "fetch" -> PROVIDER_WEB_FETCH_TOOL
    else -> PROVIDER_WEB_SEARCH_TOOL
}

private val PROVIDER_RESEARCH_TOOL_PATTERN = Regex(
    "(?:openrouter:)?web[_ -](search|fetch)",
    RegexOption.IGNORE_CASE,
)
private val PROVIDER_EXTRACT_WORD_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
private const val MIN_PROVIDER_EXTRACT_CHARS = 600
private const val MIN_PROVIDER_EXTRACT_WORDS = 50
private const val MAX_PROVIDER_RESEARCH_AUDIT_RECORDS = 32
private const val MAX_PROVIDER_EVIDENCE_CONTEXT_CHARS = 32_000

private val FUNCTION_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

internal fun parseAdaptiveResearchStrategy(
    content: String,
    minimumQueries: Int,
    enforceSemanticDiversity: Boolean = true,
): AdaptiveResearchStrategy {
    require(minimumQueries >= 2)
    val root = JsonEnvelopeParser.requireEmbeddedObject(content, "Adaptive research strategy")
    fun requiredText(name: String): String = root.optString(name)
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(2_000)
        .also { require(it.isNotBlank()) { "Adaptive research strategy omitted $name." } }
    fun stringList(name: String, minimum: Int): List<String> {
        val array = root.optJSONArray(name)
            ?: throw IllegalArgumentException("Adaptive research strategy omitted $name.")
        val values = buildList {
            for (index in 0 until array.length()) {
                array.optString(index)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.take(1_000)
                    ?.let(::add)
            }
        }.distinctBy { it.lowercase(Locale.US) }
        require(values.size >= minimum) { "Adaptive research strategy needs at least $minimum $name entries." }
        return values
    }

    val rawQueries = root.optJSONArray("queries")
        ?: throw IllegalArgumentException("Adaptive research strategy omitted queries.")
        val queries = buildList {
            for (index in 0 until rawQueries.length()) {
                val item = rawQueries.optJSONObject(index) ?: continue
                val query = item.optString("query").replace(Regex("\\s+"), " ").trim().take(500)
                val purpose = item.optString("purpose").replace(Regex("\\s+"), " ").trim().take(1_000)
                val expectedEvidence = item.optString("expected_evidence")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(1_000)
                if (query.isBlank() || purpose.isBlank() || expectedEvidence.isBlank()) continue
                
                // Strict query validation
                if (SearchQueryValidator.isProse(query)) continue
                if (SearchQueryValidator.isStaleDate(query)) continue
                
                val validatedQuery = SearchQueryValidator.validate(query, null, null)
                val queryText = if (validatedQuery is SearchQueryValidator.ValidationResult.Valid) {
                    validatedQuery.executionText
                } else {
                    continue
                }

                val entitiesArray = item.optJSONArray("entities")
                val entities = entitiesArray?.toStringList() ?: emptyList()

                val intent = ResearchSearchIntent(
                    entities = entities.takeIf { it.isNotEmpty() } ?: requestAnchorTokens(query).take(6),
                    geographicScope = item.optNullableString("geographic_scope"),
                    metric = item.optNullableString("metric"),
                    timeScope = item.optNullableString("time_scope"),
                    comparisonClass = item.optNullableString("comparison_class"),
                    unresolvedFact = purpose,
                    requiredSourceRole = ResearchPassRole.GENERAL,
                )
                
                add(
                    AdaptiveResearchQuery(
                        query = queryText,
                        purpose = purpose,
                        expectedEvidence = expectedEvidence,
                        dependsOnDiscovery = item.optString("depends_on_discovery")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .takeIf { (it.isNotBlank()) && (it != "null") }
                            ?.take(1_000),
                        intent = intent,
                    )
                )
            }
        }.distinctBy { it.query.lowercase(Locale.US) }
    require(queries.size >= minimumQueries) {
        "Adaptive research strategy needs at least $minimumQueries distinct executable queries."
    }
    require(queries.map { it.purpose.lowercase(Locale.US) }.distinct().size >= minimumQueries) {
        "Adaptive research queries must investigate distinct unknowns rather than paraphrase one purpose."
    }
    if (enforceSemanticDiversity) {
        require(researchQueriesHaveDistinctInformationNeeds(queries.take(minimumQueries))) {
            "Adaptive research queries repeat one information need instead of branching from the request."
        }
    }

    return AdaptiveResearchStrategy(
        interpretation = requiredText("interpretation"),
        decisionTarget = requiredText("decision_target"),
        scopeAmbiguities = stringList("scope_ambiguities", 1),
        unknowns = stringList("unknowns", minimumQueries),
        evidenceTargets = stringList("evidence_targets", minimumQueries),
        falsifiers = stringList("falsifiers", 2),
        followUpRule = requiredText("follow_up_rule"),
        queries = queries,
    )
}

    private fun researchQueriesHaveDistinctInformationNeeds(queries: List<AdaptiveResearchQuery>): Boolean {
        val tokenSets = queries.map { item ->
            "${item.query} ${item.purpose} ${item.expectedEvidence}"
                .lowercase(Locale.US)
                .split(Regex("[^a-z0-9]+"))
                .asSequence()
                .filter { (it.length >= 3) && (it !in RESEARCH_QUERY_STOP_WORDS) }
                .toSet()
        }
        if (tokenSets.any { it.size < 3 }) return false
        return tokenSets.indices.all { left ->
            tokenSets.indices.asSequence().filter { it != left }.all { right ->
                val union = tokenSets[left] union tokenSets[right]
                val overlap = tokenSets[left] intersect tokenSets[right]
                (union.isNotEmpty()) && ((overlap.size.toDouble() / union.size.toDouble()) < 0.86)
            }
        }
    }

open class AgentOpenRouterClient internal constructor(
    private val toolRuntime: AutonomousToolRuntime? = null,
    private val autonomyPolicy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
    private val client: OkHttpClient = sharedClient,
    private val researchMonitor: ResearchMonitor? = null,
    private val diagnostics: com.david.openassistant.data.diagnostics.RuntimeDiagnostics? = null,
    private val store: AgentStore? = null,
    private val terminalHook: TerminalTransitionHook? = null,
    private val postActiveHook: PostActivePreDispatchHook? = null,
) {
    private val missionClient: OkHttpClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .eventListenerFactory { TransportEventListener(store) }
        .build()

    internal class TransportTracker {
        @Volatile var stage = ProviderTransportStage.NOT_DISPATCHED
        @Volatile var certainty = ProviderDeliveryCertainty.NOT_SENT
    }

    internal class TransportEventListener(private val store: AgentStore?) : okhttp3.EventListener() {
        private fun update(call: okhttp3.Call, tracker: TransportTracker) {
            val context = call.request().tag(ProviderTransportContext::class.java) ?: return
            store?.updateProviderTransportStage(context.goalId, context.exchangeId, tracker.stage, tracker.certainty)
        }

        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            call.request().tag(TransportTracker::class.java)?.let {
                it.stage = ProviderTransportStage.CONNECTING
                update(call, it)
            }
        }
        override fun requestHeadersStart(call: okhttp3.Call) {
            call.request().tag(TransportTracker::class.java)?.let {
                it.stage = ProviderTransportStage.REQUEST_HEADERS_SENT
                update(call, it)
            }
        }
        override fun requestBodyStart(call: okhttp3.Call) {
            call.request().tag(TransportTracker::class.java)?.let {
                it.stage = ProviderTransportStage.REQUEST_BODY_STARTED
                it.certainty = ProviderDeliveryCertainty.SENT_UNCONFIRMED
                update(call, it)
            }
        }
        override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) {
            call.request().tag(TransportTracker::class.java)?.let {
                it.stage = ProviderTransportStage.REQUEST_BODY_COMPLETE
                update(call, it)
            }
        }
        override fun responseHeadersStart(call: okhttp3.Call) {
            call.request().tag(TransportTracker::class.java)?.let {
                it.stage = ProviderTransportStage.RESPONSE_HEADERS_RECEIVED
                update(call, it)
            }
        }
        override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
            call.request().tag(TransportTracker::class.java)?.let {
                it.stage = ProviderTransportStage.RESPONSE_BODY_READING
                it.certainty = ProviderDeliveryCertainty.RESPONSE_CONFIRMED
                update(call, it)
            }
        }
    }
    internal fun interface TerminalTransitionHook {
        fun onTerminalTransition(
            goalId: String,
            exchangeId: String,
            parentOperationId: String,
            intendedOutcome: ExchangeOutcome
        )
    }

    internal fun interface PostActivePreDispatchHook {
        fun afterActivePersisted(goalId: String, exchangeId: String)
    }

    var currentSessionId: String? = null
    private val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
    @Volatile private var isCancelled = false

    /** Immediately interrupts provider I/O when a mission cancellation is signalled. */
    fun cancelActiveCalls() {
        isCancelled = true
        activeCalls.toList().forEach(okhttp3.Call::cancel)
        activeCalls.clear()
    }

    /** Returns a prioritized list of compatible models for OpenRouter request-level fallback. */
    suspend fun fetchModels(apiKey: String): List<OpenRouterModel> {
        val cached = catalogCache.get()
        val targetSessionId = currentSessionId ?: researchMonitor?.status()?.sessionId
        if (cached != null) {
            researchMonitor?.record(
                category = "provider",
                event = "cache_hit",
                correlationId = "catalog",
                targetSessionId = targetSessionId,
                fields = mapOf("provider" to "OpenRouter", "operation" to "fetch_model_catalog", "model_count" to cached.size),
            )
            return cached
        }

        return catalogLock.withLock {
            val doublyChecked = catalogCache.get()
            if (doublyChecked != null) return@withLock doublyChecked

            val request = Request.Builder()
                .url(MODELS_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .header("X-OpenRouter-Title", "OpenAssistant Android")
                .header("User-Agent", "OpenAssistant-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            val exchangeId = "openrouter-${UUID.randomUUID()}"
            val startedAt = System.currentTimeMillis()
            researchMonitor?.record(
                category = "provider",
                event = "cache_refresh",
                correlationId = exchangeId,
                targetSessionId = targetSessionId,
                fields = mapOf(
                    "provider" to "OpenRouter",
                    "operation" to "fetch_model_catalog",
                    "method" to "GET",
                    "endpoint" to MODELS_URL,
                ),
            )
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    val success = response.isSuccessful
                    researchMonitor?.record(
                        category = "provider",
                        event = "response",
                        level = if (success) "INFO" else "ERROR",
                        correlationId = exchangeId,
                        targetSessionId = targetSessionId,
                        fields = mapOf(
                            "provider" to "OpenRouter",
                            "operation" to "fetch_model_catalog",
                            "http_status" to response.code,
                            "successful" to success,
                            "duration_ms" to (System.currentTimeMillis() - startedAt),
                            "response_bytes" to body.toByteArray().size,
                        ),
                    )
                    if (!success) {
                        val stale = catalogCache.getStale()
                        if (stale != null) {
                            researchMonitor?.record(
                                category = "provider",
                                event = "stale_cache_used",
                                correlationId = exchangeId,
                                targetSessionId = targetSessionId,
                                fields = mapOf("reason" to "Network failure during refresh", "status" to response.code),
                            )
                            return@withLock stale
                        }
                        return@withLock emptyList()
                    }
                    val models = parseModels(body)
                    if (models.isNotEmpty()) catalogCache.set(models)
                    models
                }
            } catch (error: Throwable) {
                researchMonitor?.record(
                    category = "provider",
                    event = "refresh_failed",
                    level = "ERROR",
                    correlationId = exchangeId,
                    targetSessionId = targetSessionId,
                    fields = mapOf("error" to error.message.orEmpty()),
                )
                catalogCache.getStale() ?: emptyList()
            }
        }
    }

    private fun parseModels(body: String): List<OpenRouterModel> {
        val data = runCatching {
            JSONObject(body).optJSONArray("data")
        }.getOrNull() ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue

                val pricing = item.optJSONObject("pricing")
                val architecture = item.optJSONObject("architecture")
                add(
                    OpenRouterModel(
                        id = id,
                        name = item.optString("name", id),
                        description = item.optString("description"),
                        contextLength = item.optInt("context_length", 0),
                        inputModalities = architecture?.optJSONArray("input_modalities").toStringList(),
                        outputModalities = architecture?.optJSONArray("output_modalities").toStringList(),
                        supportedParameters = item.optJSONArray("supported_parameters").toStringList().toSet(),
                        promptPricePerToken = pricing?.optString("prompt").toNullableDouble(),
                        completionPricePerToken = pricing?.optString("completion").toNullableDouble(),
                    ),
                )
            }
        }.sortedWith(compareByDescending<OpenRouterModel> { it.isFree }.thenBy { it.name.lowercase() })
    }

    private fun String?.toNullableDouble(): Double? =
        this?.takeIf { (it.isNotBlank()) && (it != "null") }?.toDoubleOrNull()

    suspend fun generateResearchBrief(
        apiKey: String,
        modelId: String,
        conversationHistory: String,
    ): Pair<ResearchDraft, AgentApiSummary> {
        val prompt = BriefingPrompts.briefingUserPrompt(conversationHistory)
        
        fun briefingPayload(system: String, user: String, selection: String): Pair<JSONObject, ProviderResponseAttribution> {
            val (payload, attribution) = basePayload(
                modelId = modelId,
                systemPrompt = system,
                userPrompt = user,
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = selection,
                freeOnly = false
            )
            payload.put("temperature", 0.1)
            return payload to attribution
        }

        val strict = briefingPayload(BriefingPrompts.BRIEFING_SYSTEM_PROMPT, prompt, "research_briefing")
        strict.first.put("response_format", jsonSchemaResponseFormat("research_brief_v1", researchBriefSchema()))

        val jsonMode = briefingPayload(
            BriefingPrompts.BRIEFING_SYSTEM_PROMPT,
            "$prompt\nReturn one valid JSON object matching the requested structure and no markdown.",
            "research_briefing_json"
        )
        jsonMode.first.put("response_format", JSONObject().put("type", "json_object"))

        val plain = briefingPayload(
            BriefingPrompts.BRIEFING_SYSTEM_PROMPT,
            "$prompt\nReturn one valid JSON object and no markdown or surrounding explanation.",
            "research_briefing_plain"
        )

        val response = executeBriefingStructuredWithFallback(
            apiKey = apiKey,
            strict = strict,
            jsonMode = jsonMode,
            plain = plain
        )
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Research brief")
        val draft = ResearchDraft(
            conversationId = "", // Filled by interactor
            title = root.optString("title").trim(),
            question = root.optString("question").trim(),
            objective = root.optString("objective").trim(),
            confirmedConstraints = root.optJSONArray("confirmed_constraints").toStringList(),
            inferredPreferences = root.optJSONArray("inferred_preferences").toStringList(),
            unresolvedQuestions = root.optJSONArray("unresolved_questions").toStringList(),
            evidenceRequirements = root.optJSONArray("evidence_requirements").toStringList(),
            preferredSourceTypes = root.optJSONArray("preferred_source_types").toStringList(),
            freshnessRequirement = root.optNullableString("freshness_requirement"),
            exclusions = root.optJSONArray("exclusions").toStringList(),
            desiredDeliverable = root.optString("desired_deliverable").trim().ifBlank {
                "A source-traceable research result that reconciles evidence, counterevidence, methodology, and unresolved uncertainty."
            },
        )
        return draft to response.summary
    }

    private fun researchBriefSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("question", JSONObject().put("type", "string"))
                .put("objective", JSONObject().put("type", "string"))
                .put("confirmed_constraints", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("inferred_preferences", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("unresolved_questions", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("evidence_requirements", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("preferred_source_types", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("freshness_requirement", JSONObject().put("type", JSONArray(listOf("string", "null"))))
                .put("exclusions", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("desired_deliverable", JSONObject().put("type", "string")),
        )
        .put("required", JSONArray(listOf("title", "question", "objective", "confirmed_constraints", "inferred_preferences", "unresolved_questions", "evidence_requirements", "preferred_source_types", "freshness_requirement", "exclusions", "desired_deliverable")))
        .put("additionalProperties", false)

    open suspend fun createResearchRecoveryProposal(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        plan: ResearchRecoveryPlan,
        evidence: List<AgentEvidence>,
        freeOnly: Boolean,
        requestContext: ProviderRequestContext.Mission,
    ): RecoveryProposalGenerationResult {
        val generation = requestContext.executionGeneration
        val systemPrompt = "You are an expert Research Recovery Analyst."
        val userPrompt = buildString {
            appendLine("The current research task has stalled. Your goal is to propose a materially novel investigation strategy.")
            appendLine("Diagnosis: ${plan.diagnosis.name}")
            appendLine("Selected Tactic: ${plan.selectedTactic.name}")
            appendLine()
            appendLine("Current Operational Objective: ${goal.objective}")
            appendLine("Constraints: ${goal.confirmedConstraints.joinToString(", ")}")
            appendLine()
            appendLine("Available Evidence Context:")
            evidence.forEach { item ->
                appendLine("- [${item.kind}] ${item.title}: ${item.summary}")
                if (plan.selectedTactic == EscalationTactic.FOLLOW_CITATIONS) {
                    val explicitUrls = item.sources.mapTo(mutableSetOf()) { it.url }
                    val embeddedLinks = recoverHttpsSourceCitations(item.content).filter { it.url !in explicitUrls }
                    if (embeddedLinks.isNotEmpty()) {
                        appendLine("  Embedded cross-domain citations found in this content:")
                        embeddedLinks.take(5).forEach { link ->
                            appendLine("  - ${link.url}")
                        }
                    }
                }
            }
            if (plan.selectedTactic == EscalationTactic.FOLLOW_CITATIONS) {
                appendLine()
                appendLine("INSTRUCTION: The selected tactic is FOLLOW_CITATIONS. You MUST include exact URLs from the 'Embedded cross-domain citations' above in your new_query_portfolio to fetch and explore out-of-domain sources. The system supports direct URL fetching when you provide an exact URL as a search query.")
            }
        }

        try {
            val response = executeStructuredWithFallback(
                apiKey = apiKey,
                strict = basePayload(modelId, systemPrompt, userPrompt, reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium", role = AgentTaskRole.PRIMARY_REASONING, selectionReason = "recovery_proposal", freeOnly = freeOnly).let { (p, attr) ->
                    p.put("response_format", jsonSchemaResponseFormat("research_recovery_proposal", recoveryProposalSchema()))
                    p.put("temperature", 0.1)
                    p to attr
                },
                jsonMode = basePayload(
                    modelId,
                    systemPrompt,
                    "$userPrompt\nReturn one valid JSON object matching the requested structure and no markdown.",
                    reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                    role = AgentTaskRole.PRIMARY_REASONING,
                    selectionReason = "recovery_proposal_json_mode",
                    freeOnly = freeOnly
                ).let { (p, attr) ->
                    p.put("response_format", JSONObject().put("type", "json_object"))
                    p.put("temperature", 0.1)
                    p to attr
                },
                plain = basePayload(
                    modelId,
                    systemPrompt,
                    "$userPrompt\nReturn one valid JSON object and no markdown or surrounding explanation.",
                    reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                    role = AgentTaskRole.PRIMARY_REASONING,
                    selectionReason = "recovery_proposal_plain",
                    freeOnly = freeOnly
                ).let { (p, attr) ->
                    p.put("temperature", 0.1)
                    p to attr
                },
                generation = generation,
                requestContext = requestContext,
            )

            val proposal = response.reconciledProposal ?: parseRecoveryProposal(response.content)
            return RecoveryProposalGenerationResult.ProposalAvailable(proposal, response.summary, response.summary.responseId ?: "", false)
        } catch (e: ReconciliationException) {
            return when (val result = e.result) {
                is MissionDispatchResult.ReusedDurableSuccess -> {
                    val proposal = parseRecoveryProposal(result.body)
                    RecoveryProposalGenerationResult.ProposalAvailable(proposal, null, result.exchangeId, true)
                }
                is MissionDispatchResult.Reconciled -> {
                    if (result.responseContent != null) {
                        val proposal = parseRecoveryProposal(result.responseContent)
                        RecoveryProposalGenerationResult.ProposalAvailable(proposal, result.summary, result.exchangeId, true)
                    } else if (result.proposal != null) {
                        RecoveryProposalGenerationResult.ProposalAvailable(result.proposal, result.summary, result.exchangeId, true)
                    } else {
                        RecoveryProposalGenerationResult.AlternateStrategyRequired(ProviderReconciliationFailureKind.SUCCESS_RESULT_MISSING, "Reconciled but missing proposal content", null)
                    }
                }
                is MissionDispatchResult.ExistingAmbiguous -> RecoveryProposalGenerationResult.ReconciliationRequired(result.attempt, ProviderReconciliationFailureKind.DELIVERY_AMBIGUOUS, "Operation delivery is ambiguous; zero-replay policy prevents automatic dispatch.")
                is MissionDispatchResult.ExistingInFlight -> RecoveryProposalGenerationResult.ReconciliationRequired(result.attempt, ProviderReconciliationFailureKind.EXISTING_IN_FLIGHT, "Existing active request owned by another worker or session.")
                is MissionDispatchResult.ExistingNotDispatched -> RecoveryProposalGenerationResult.RetryableTransportFailure(
                    FailureDescriptor(FailureDomain.PROVIDER, "NOT_DISPATCHED", FailureScope.REQUEST, RetryPolicy.IMMEDIATE_AFTER_LOCAL_REPAIR, null, null, null, null, null, null, null, "Not dispatched"),
                    result.attempt
                )
                is MissionDispatchResult.ExistingTerminalFailure -> RecoveryProposalGenerationResult.AlternateStrategyRequired(ProviderReconciliationFailureKind.EXISTING_TERMINAL_FAILURE, "Operation has already reached a terminal failure state.", result.attempt)
                is MissionDispatchResult.LogicalIdentityConflict -> RecoveryProposalGenerationResult.ReconciliationRequired(ProviderRequestAttempt(exchangeId = "conflict", logicalRequestId = requestContext.logicalRequestId ?: "", executionGeneration = requestContext.executionGeneration, parentOperationId = requestContext.parentOperationId, goalId = requestContext.goalId, requestedModel = modelId, payloadFingerprint = ""), ProviderReconciliationFailureKind.LOGICAL_IDENTITY_CONFLICT, "Logical request ID conflict: payload fingerprint mismatch.")
                is MissionDispatchResult.OwnershipRejected -> RecoveryProposalGenerationResult.NeedsUserAction(ProviderReconciliationFailureKind.OWNERSHIP_REJECTED, result.reason)
                is MissionDispatchResult.RetryAuthorizationRequired -> RecoveryProposalGenerationResult.NeedsUserAction(ProviderReconciliationFailureKind.RETRY_AUTHORIZATION_REQUIRED, "Retry requires explicit authorization.")
                is MissionDispatchResult.StorageFailure -> RecoveryProposalGenerationResult.StorageFailure(result.cause)
                is MissionDispatchResult.Success -> throw IllegalStateException("Success should not throw ReconciliationException")
            }
        }
    }

    private fun recoveryProposalSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("revised_investigation_interpretation", JSONObject().put("type", "string"))
            .put("specific_unresolved_gap", JSONObject().put("type", "string"))
            .put("selected_source_family_shift", JSONObject().put("type", "string").put("nullable", true))
            .put("evidence_targets", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("falsifiers", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("new_query_portfolio", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
            .put("follow_up_rule", JSONObject().put("type", "string").put("nullable", true))
            .put("rationale", JSONObject().put("type", "string"))
            .put("expected_novelty_dimensions", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
        )
        .put("required", JSONArray(listOf(
            "revised_investigation_interpretation", "specific_unresolved_gap", "evidence_targets",
            "falsifiers", "new_query_portfolio", "rationale", "expected_novelty_dimensions"
        )))

    private fun parseRecoveryProposal(content: String): RecoveryProposal {
        val json = JSONObject(content)
        return RecoveryProposal(
            revisedInvestigationInterpretation = json.getString("revised_investigation_interpretation"),
            specificUnresolvedGap = json.getString("specific_unresolved_gap"),
            selectedSourceFamilyShift = json.optNullableString("selected_source_family_shift"),
            evidenceTargets = json.getJSONArray("evidence_targets").toStringList(),
            falsifiers = json.getJSONArray("falsifiers").toStringList(),
            newQueryPortfolio = json.getJSONArray("new_query_portfolio").toStringList(),
            followUpRule = json.optNullableString("follow_up_rule"),
            rationale = json.getString("rationale"),
            expectedNoveltyDimensions = json.getJSONArray("expected_novelty_dimensions").toStringList()
        )
    }

    suspend fun createCycleAdvancementProposal(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        sourceCycle: ResearchCycle,
        learningSummary: ResearchCycleLearningSummary,
        freeOnly: Boolean,
        requestContext: ProviderRequestContext.Mission,
    ): Pair<AgentPlanDraft, AgentApiSummary> {
        val generation = requestContext.executionGeneration
        val systemPrompt = "You are an expert Research Strategist."
        val userPrompt = buildString {
            appendLine("The current research cycle has been exhausted. You must propose a successor cycle with a refined operational objective and a new task plan.")
            appendLine("Immutable Root Objective: ${goal.objective}")
            appendLine("Current Cycle Ordinal: ${sourceCycle.ordinal}")
            appendLine()
            appendLine("Learning Summary from Current Cycle:")
            appendLine("- Findings: ${learningSummary.establishedFindings.joinToString("; ")}")
            appendLine("- Remaining Gaps: ${learningSummary.remainingUnresolvedGaps.joinToString("; ")}")
            appendLine("- Rejected Material: ${learningSummary.rejectedOrUnreliableMaterial.joinToString("; ")}")
            appendLine("- Advancement Reason: ${learningSummary.advancementReason}")
            appendLine()
            appendLine("Your task is to refine the OPERATIONAL objective (without changing the root subject) and provide a new set of tasks to close the remaining gaps.")
        }

        val response = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = basePayload(modelId, systemPrompt, userPrompt, reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium", role = AgentTaskRole.PRIMARY_REASONING, selectionReason = "cycle_advancement", freeOnly = freeOnly).let { (p, attr) ->
                p.put("response_format", jsonSchemaResponseFormat("cycle_advancement_plan", planSchema()))
                p.put("temperature", 0.1)
                p to attr
            },
            jsonMode = basePayload(
                modelId,
                systemPrompt,
                "$userPrompt\nReturn one valid JSON object matching the requested structure and no markdown.",
                reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "cycle_advancement_json_mode",
                freeOnly = freeOnly
            ).let { (p, attr) ->
                p.put("response_format", JSONObject().put("type", "json_object"))
                p.put("temperature", 0.1)
                p to attr
            },
            plain = basePayload(
                modelId,
                systemPrompt,
                "$userPrompt\nReturn one valid JSON object and no markdown or surrounding explanation.",
                reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "cycle_advancement_plain",
                freeOnly = freeOnly
            ).let { (p, attr) ->
                p.put("temperature", 0.1)
                p to attr
            },
            generation = generation,
            requestContext = requestContext,
        )

        val draft = response.reconciledProposal?.let {
            AgentPlanDraft(
                title = goal.title,
                objective = it.revisedInvestigationInterpretation,
                finalOutputDescription = goal.finalOutputDescription,
                acceptanceCriteria = it.evidenceTargets.map { desc -> AgentAcceptanceCriterion(UUID.randomUUID().toString(), desc) },
                tasks = emptyList() // Will be reconstructed by planner commit logic
            )
        } ?: parsePlan(response.content, freeOnly)
        return draft to response.summary
    }

    suspend fun createPlan(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        freeOnly: Boolean = false,
        requestContext: ProviderRequestContext.Mission,
    ): Pair<AgentPlanDraft, AgentApiSummary> {
        val generation = requestContext.executionGeneration
        val researchPolicy = AgentResearchPolicy.forRequest(
            request = goal.userRequest,
            deepResearchByDefault = autonomyPolicy.deepResearchByDefault,
        )
        val prompt = buildString {
            appendLine("Lead investigator: understand the request on its own terms and create a request-specific investigation plan. Do not use a generic template.")
            appendLine("Identify the core person, organization, product, event, or technical standard. Resolve ambiguities and identify material unknowns.")
            if (researchPolicy.requiresResearch) {
                val passes = if (researchPolicy.minimumPasses >= 4) {
                    "discovery, primary-source verification, adversarial contradiction/disconfirmation, and gap/freshness closure"
                } else {
                    "discovery, primary-source verification, and adversarial contradiction/disconfirmation"
                }
                appendLine("Plan exactly one initial analysis (reason), exactly ${researchPolicy.minimumPasses} focused research passes (deep_research) covering $passes, and one final integration (synthesize).")
            }
            appendLine("Every milestone title, instruction, and acceptance criterion must identify subject-specific work. Do not write generic tasks.")
            appendLine("A success criterion must be satisfiable by documenting an exhaustive negative finding and revising the answer honestly.")

            appendLine()
            appendLine("User request:")
            appendLine(boundedText(goal.userRequest, MAX_PLANNER_REQUEST_CHARS))

            append(missionContextPrompt(goal))
        }

        val response = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = basePayload(modelId, PLANNER_SYSTEM_PROMPT, prompt, reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium", role = AgentTaskRole.PRIMARY_REASONING, selectionReason = "initial_plan", freeOnly = freeOnly).let { (p, attr) ->
                p.put("response_format", jsonSchemaResponseFormat("agent_plan_v4", planSchema()))
                p.put("temperature", 0.1)
                p to attr
            },
            jsonMode = basePayload(
                modelId,
                PLANNER_SYSTEM_PROMPT,
                "$prompt\nReturn one valid JSON object matching the requested structure and no markdown.",
                reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "initial_plan_json_mode",
                freeOnly = freeOnly
            ).let { (p, attr) ->
                p.put("response_format", JSONObject().put("type", "json_object"))
                p.put("temperature", 0.1)
                p to attr
            },
            plain = basePayload(
                modelId,
                PLANNER_SYSTEM_PROMPT,
                "$prompt\nReturn one valid JSON object and no markdown or surrounding explanation.",
                reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "initial_plan_plain",
                freeOnly = freeOnly
            ).let { (p, attr) ->
                p.put("temperature", 0.1)
                p to attr
            },
            generation = generation,
            requestContext = requestContext,
        )
        val parsedFirstDraft = runCatching {
            parsePlan(response.content, requireSynthesis = false)
        }.getOrNull()
        val recoveredFirstDraft = parsedFirstDraft
            ?.takeIf { it.hasRequestSpecificPlanMaterialFor(goal.userRequest) }
            ?.let { recoverNearCompletePlanTail(it, researchPolicy, goal.userRequest) }
        val firstDraft = recoveredFirstDraft?.plan ?: parsedFirstDraft
        val accountedResponse: RawAgentResponse
        val parsedDraft: AgentPlanDraft
        if (firstDraft != null && firstDraft.isRequestSpecificPlanFor(researchPolicy, goal.userRequest)) {
            if (recoveredFirstDraft?.changed == true) {
                recordPlanTailRecovery(response, recoveredFirstDraft, "initial")
            }
            accountedResponse = response
            parsedDraft = firstDraft
        } else {
            val refined = try {
                executePlanRefinement(
                    apiKey = apiKey,
                    modelId = modelId,
                    request = goal.userRequest,
                    policy = researchPolicy,
                    rejectedPlan = response.content,
                    freeOnly = freeOnly,
                    generation = generation,
                    requestContext = requestContext.forChildOperation(
                        MissionOperation.PLAN_REFINEMENT,
                        AgentTaskRole.PRIMARY_REASONING,
                        taskId = null,
                    ),
                )
            } catch (error: OpenRouterException) {
                throw error.withPlanningUsage(response.summary)
            }
            accountedResponse = response.mergeRepair(refined)
            val parsedRefinedDraft = runCatching {
                parsePlan(refined.content, requireSynthesis = false)
            }.getOrNull()
            val recoveredRefinedDraft = parsedRefinedDraft
                ?.takeIf { it.hasRequestSpecificPlanMaterialFor(goal.userRequest) }
                ?.let { candidate -> recoverNearCompletePlanTail(candidate, researchPolicy, goal.userRequest) }
            val refinedDraft = recoveredRefinedDraft?.plan ?: parsedRefinedDraft
            parsedDraft = if (refinedDraft != null && refinedDraft.isRequestSpecificPlanFor(researchPolicy, goal.userRequest)) {
                if (recoveredRefinedDraft?.changed == true) {
                    recordPlanTailRecovery(refined, recoveredRefinedDraft, "refinement")
                }
                refinedDraft
            } else {
                val deterministicDraft = DeterministicPlanFallback.build(goal, researchPolicy)
                if (!deterministicDraft.isRequestSpecificPlanFor(researchPolicy, goal.userRequest)) {
                    throw planningFailure(
                        message = "The planner returned a generic, structurally incomplete, or explicitly stale investigation plan, and deterministic planning recovery could not create a durable request-specific plan.",
                        summary = accountedResponse.summary,
                    )
                }
                researchMonitor?.record(
                    category = "runtime",
                    event = "agent_plan_deterministic_recovery_used",
                    correlationId = accountedResponse.summary.responseId,
                    targetSessionId = currentSessionId,
                    fields = mapOf(
                        "goal_id" to goal.id,
                        "request_anchor" to extractCompactAnchor(goal.userRequest),
                        "provider_retry_avoided" to true,
                        "facts_sources_or_claims_added" to false,
                    ),
                )
                deterministicDraft
            }
        }
        val draft = AgentPlanEnhancer.enhance(
            draft = boundEvidenceContingentPlanCriteria(parsedDraft),
            policy = researchPolicy,
        )
        
        // V41: Populate ObjectiveContract during planning
        val contract = ObjectiveContract(
            version = 1,
            primarySubject = draft.title,
            strongAnchors = requestAnchorTokens(goal.userRequest, draft.tasks.map { it.instructions }),
            temporalContext = draft.tasks.firstOrNull { it.capability == AgentCapability.REASON }?.instructions?.take(500),
            expectedDeliverableKind = draft.finalOutputDescription.take(500),
            domainClassification = "GENERAL" 
        ).let { c ->
            val hashInput = "${c.version}|${c.primarySubject}|${c.strongAnchors.joinToString(",")}|${c.temporalContext}|${c.expectedDeliverableKind}|${c.domainClassification}"
            val hash = UUID.nameUUIDFromBytes(hashInput.toByteArray(Charsets.UTF_8)).toString()
            c.copy(contractHash = hash)
        }

        return (draft to accountedResponse.summary).let { (d, s) ->
            d.copy(objectiveContract = contract) to s
        }
    }

    private fun recordPlanTailRecovery(
        response: RawAgentResponse,
        recovered: RecoveredPlanTail,
        stage: String,
    ) {
        researchMonitor?.record(
            category = "runtime",
            event = "agent_plan_tail_recovered",
            correlationId = response.summary.responseId,
            targetSessionId = currentSessionId,
            fields = mapOf(
                "stage" to stage,
                "added_research_milestones" to recovered.addedResearchMilestones,
                "added_synthesis_milestone" to recovered.addedSynthesisMilestone,
                "reclassified_synthesis_milestone" to recovered.reclassifiedSynthesisMilestone,
                "provider_retry_avoided" to true,
                "facts_sources_or_claims_added" to false,
            ),
        )
    }

    private suspend fun executePlanRefinement(
        apiKey: String,
        modelId: String,
        request: String,
        policy: AgentResearchPolicy,
        rejectedPlan: String,
        freeOnly: Boolean = false,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): RawAgentResponse {
        val requiredRoles = if (policy.minimumPasses >= 4) {
            "discovery, primary-source verification, adversarial contradiction/disconfirmation, and gap/freshness closure"
        } else {
            "discovery, primary-source verification, and adversarial contradiction/disconfirmation"
        }
        val prompt = buildString {
            appendLine("The prior planner response was malformed, structurally incomplete, or too generic. Rebuild it from the exact request instead of filling a reusable template.")
            appendLine("Infer the request's domain, intended outcome, operational definitions, ambiguities, material unknowns, evidence types, and answer-changing conditions. Every milestone title, instruction, and acceptance criterion must identify subject-specific work.")
            appendLine("Do not make success depend on a predetermined consensus, exact measurement, controlled comparison, dataset, or number of favorable sources existing. A criterion must also be satisfiable by documenting an exhaustive negative finding and revising the answer honestly.")
            if (policy.requiresResearch) {
                appendLine("Return exactly one request-analysis reason milestone, exactly ${policy.minimumPasses} deep_research milestones in this order—$requiredRoles—and one final synthesis milestone. Optional deterministic tool milestones may follow the research passes and precede synthesis.")
                appendLine("Do not write generic tasks that could be pasted unchanged onto another subject. The role names are quality controls only; derive the actual investigation content from this request.")
            }
            appendLine()
            appendLine("Exact user request:")
            appendLine(boundedText(request, MAX_PLANNER_REQUEST_CHARS))
            appendLine()
            appendLine("Rejected planner response, supplied only so structural mistakes are not repeated:")
            appendLine(rejectedPlan.take(MAX_STRUCTURE_REPAIR_CHARS))
        }
        fun payload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = when {
                    freeOnly -> ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID
                    ProviderRecoveryPolicy.isAutoRouter(modelId) -> modelId
                    else -> ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID
                },
                systemPrompt = PLANNER_REFINEMENT_SYSTEM_PROMPT,
                userPrompt = prompt,
                reasoningEffort = if (freeOnly || isFreeOnlyModel(modelId)) "high" else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "plan_refinement",
                freeOnly = freeOnly
            )
            p.apply {
                put("temperature", 0.08)
                responseFormat?.let { put("response_format", it) }
            }
            return p to attr
        }
        return executeStructuredWithFallback(
            apiKey = apiKey,
            strict = payload(jsonSchemaResponseFormat("agent_plan_refinement_v1", planSchema())),
            jsonMode = payload(JSONObject().put("type", "json_object")),
            plain = payload(null),
            generation = generation,
            requestContext = requestContext,
        )
    }

    private fun AgentPlanDraft.isRequestSpecificPlanFor(
        policy: AgentResearchPolicy,
        request: String,
    ): Boolean {
        if (!hasRequestSpecificPlanMaterialFor(request)) return false
        if (tasks.none { it.capability == AgentCapability.SYNTHESIZE }) return false
        if (!policy.requiresResearch) return tasks.all { it.title.isNotBlank() && it.instructions.isNotBlank() }

        val reasoning = tasks.filter { it.capability == AgentCapability.REASON }
        val research = tasks.filter { it.capability == AgentCapability.DEEP_RESEARCH }
        if (reasoning.isEmpty() || research.size != policy.minimumPasses) return false
        if (research.any { it.instructions.length < 80 || it.acceptanceCriteria.isEmpty() }) return false
        if (research.map { it.title.lowercase(Locale.US) }.distinct().size != research.size) return false
        if (research.map { it.instructions.lowercase(Locale.US) }.distinct().size != research.size) return false
        if (research.flatMap { it.acceptanceCriteria }.map { it.description.lowercase(Locale.US) }.distinct().size < research.size) return false
        if (!researchPlanMaintainsRequestContext(request, research)) return false
        return reasoning.all { it.instructions.length >= 60 && it.acceptanceCriteria.isNotEmpty() }
    }

    private fun AgentPlanDraft.hasRequestSpecificPlanMaterialFor(request: String): Boolean {
        if (title.isBlank() || objective.isBlank() || finalOutputDescription.isBlank()) return false
        if (acceptanceCriteria.isEmpty() || tasks.isEmpty()) return false
        val completePlanText = buildString {
            appendLine(title)
            appendLine(objective)
            appendLine(finalOutputDescription)
            acceptanceCriteria.forEach { appendLine(it.description) }
            tasks.forEach { task ->
                appendLine(task.title)
                appendLine(task.instructions)
                task.acceptanceCriteria.forEach { appendLine(it.description) }
            }
        }
        return requestSpecificMaterialAnchorsRequest(request, completePlanText) &&
            planTemporalScopeIsCurrent(request, completePlanText)
    }

    internal fun buildEvidenceContext(goal: AgentGoal, task: AgentTask, priorEvidence: List<AgentEvidence>): String {
        val sourceReadsByCanonicalUrl = goal.sourceReads.associateBy { it.canonicalUrl }
        return priorEvidence
            .joinToString("\n\n") { evidence ->
                buildString {
                    appendLine("[evidence_id=${evidence.id}; kind=${evidence.kind.name.lowercase()}]")
                    appendLine(evidence.title)
                    appendLine(evidence.content.take(MAX_EVIDENCE_CHARS_PER_ITEM))
                    if (evidence.sources.isNotEmpty()) {
                        val sourcesToPresent = if (task.capability == AgentCapability.SYNTHESIZE) {
                            evidence.sources.filter { source ->
                                val canonical = ResearchQualityGate.canonicalSourceUrl(source.url)
                                val read = sourceReadsByCanonicalUrl[canonical]
                                read?.provenance in setOf(SourceReadProvenance.VERIFIED_FETCH, SourceReadProvenance.PROVIDER_EXTRACT)
                            }
                        } else {
                            evidence.sources
                        }
                        if (sourcesToPresent.isNotEmpty()) {
                            appendLine("Preserved source URLs:")
                            sourcesToPresent.take(15).forEach { source ->
                                appendLine("- ${source.title.take(MAX_SOURCE_TITLE_CHARS)}: ${source.url}")
                            }
                        }
                    }
                }
            }
            .ifBlank { "No prior evidence is available." }
    }

    suspend fun executeTask(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        task: AgentTask,
        requestContext: ProviderRequestContext.Mission,
        onProgress: (AgentSourceCitation) -> Unit = {},
        models: List<com.david.openassistant.data.openrouter.OpenRouterModel> = emptyList(),
        maxAttempts: Int = 3,
    ): AgentStepResult {
        if (isCancelled) throw CancellationException("Mission cancelled before task start")
        val generation = requestContext.executionGeneration
        val contextLimit = models.firstOrNull { it.id == modelId }?.contextLength ?: 128_000
        val maxContextChars = (contextLimit * 3.5).toInt().coerceIn(20_000, 400_000)
        
        val selectedContext = EvidenceContextSelector.select(goal, task, maxCharacters = maxContextChars)
        val priorEvidence = selectedContext.evidence
        val executionStrategy = selectAgentExecutionStrategy(goal, task)
        val allocationProfile = AgentResearchAllocator.profileForGoal(goal, autonomyPolicy)
        val researchBudget = AgentResearchAllocator.budgetForTask(goal, task, allocationProfile)
        val evidenceContext = buildEvidenceContext(goal, task, priorEvidence)

        val isConstructionFailure = task.lastError?.let { error ->
            val lower = error.lowercase(Locale.US)
            RESPONSE_SHAPE_ERROR_MARKERS.any { lower.contains(it) } ||
                PLAN_STRUCTURE_ERROR_MARKERS.any { lower.contains(it) } ||
                SERVER_TOOL_ERROR_MARKERS.any { lower.contains(it) }
        } ?: false

        val useBodyBuilderForDesign = isConstructionFailure && 
            (task.attemptCount >= 2) && 
            (task.capability != AgentCapability.SYNTHESIZE) && 
            (modelId != FREE_ROUTER_MODEL_ID)
        
        val baseAllowedUrls = goal.sourceReads.map { it.url }.toSet()
        if (useBodyBuilderForDesign) {
            val designInstructions = "Repair or design a high-intelligence request for milestone: ${task.title}. Instructions: ${task.instructions}"
            val designContext = "Goal: ${goal.objective}\nPrior evidence: $evidenceContext"
            val bodyBuilderContext = requestContext.forChildOperation(
                MissionOperation.BODY_BUILDER_REQUEST,
                AgentTaskRole.REQUEST_CONSTRUCTION,
                taskId = task.id,
            )
            val generatedPayload = try {
                buildComplexRequest(apiKey, designInstructions, designContext, generation, bodyBuilderContext)
            } catch (error: Throwable) {
                null
            }
            
            if (generatedPayload != null) {
                if (isCancelled) throw CancellationException("Mission cancelled during request design")
                val bodyBuilderExecContext = requestContext.forChildOperation(
                    MissionOperation.BODY_BUILDER_GENERATED_EXECUTION,
                    AgentTaskRole.PRIMARY_REASONING,
                    taskId = task.id,
                )
                val (payload, attribution) = basePayload(
                    modelId = AUTO_BETA_ROUTER_MODEL_ID, // Use auto-beta for execution
                    systemPrompt = EXECUTOR_SYSTEM_PROMPT,
                    userPrompt = "Execute the following designed request exactly.",
                    role = AgentTaskRole.PRIMARY_REASONING,
                    selectionReason = "body_builder_execution",
                    freeOnly = false
                )
                val rawDesignResponse = executeJsonRequest(apiKey, generatedPayload, attribution, generation, bodyBuilderExecContext)
                val designResponse = rawDesignResponse.withRecoveredInlineSources(baseAllowedUrls)
                return runCatching { parseStepResponse(designResponse, goal, task) }
                    .getOrElse { error ->
                        AgentStepResult(
                            content = designResponse.content,
                            summary = designResponse.summary,
                            sources = designResponse.sources,
                            completionScore = 0.4,
                            unresolvedQuestions = listOf("Request construction failed: ${error.message}"),
                            toolExecutions = designResponse.toolExecutions
                        )
                    }
            }
        }

        AgentCapabilityRegistry.requireAllowed(task.capability)
        val criteriaText = task.acceptanceCriteria
            .joinToString("\n") { criterion ->
                "- ${criterion.id} (weight ${criterion.weight}): ${criterion.description}"
            }
            .ifBlank { "- No explicit task criteria were supplied; judge concrete completion of the task instructions." }
        val deepResearch = task.capability in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH)
        val researchRole = if (deepResearch) researchPassRole(task) else ResearchPassRole.GENERAL
        val completedSynthesisRecoveries = completedSynthesisGapRecoveryPasses(goal)
        val researchBootstrap = if (deepResearch) {
            if (executionStrategy.reuseCheckpointSources) {
                preservedResearchBootstrap(
                    evidence = priorEvidence,
                    taskId = task.id,
                )
            } else {
                prepareResearchBootstrap(
                    apiKey = apiKey,
                    modelId = modelId,
                    goal = goal,
                    task = task,
                    priorEvidence = priorEvidence,
                    budget = researchBudget,
                    generation = generation,
                    requestContext = requestContext,
                )
            }
        } else {
            ResearchBootstrap.EMPTY
        }
        val bootstrapCompletedResearchTools = deepResearch && researchBootstrap.hasCompletedResearchToolWork(
            minimumSearches = autonomyPolicy.minimumSearchQueriesPerResearchPass,
            minimumFullReads = autonomyPolicy.targetFullSourceReadsPerResearchPass,
            minimumDomains = 2,
        )
        val allowInteractiveToolsForCall =
            executionStrategy.allowsInteractiveTools
        val taskToolPlan = if (allowInteractiveToolsForCall) {
            buildTaskToolPlan(
                task = task,
                modelId = modelId,
                focusedRecovery = executionStrategy.profile == AgentExecutionProfile.FOCUSED_TOOL,
                networkAvailable = toolRuntime?.isNetworkAvailable() ?: true,
                credentialsAvailable = AgentOperationalState.areCredentialsAvailable(apiKey),
                publicWebConfigured = toolRuntime?.isPublicWebConfigured() ?: true,
                goalId = goal.id
            )
        } else {
            TaskToolPlan.EMPTY
        }

        val prompt = buildString {
            appendLine("Original user request:")
            appendLine(boundedText(goal.userRequest, MAX_EXECUTOR_REQUEST_CHARS))
            append(missionContextPrompt(goal))
            appendLine()
            appendLine("Overall objective:")
            appendLine(boundedText(goal.objective, MAX_OBJECTIVE_CHARS))
            appendLine()
            appendLine("Current autonomous milestone: ${task.title}")
            appendLine(task.instructions)
            appendLine()
            appendLine("MILESTONE BOUNDARY:")
            appendLine(milestoneBoundaryInstruction(task.capability))
            appendLine("The overall objective and durable plan are context only. Grade completion solely against this milestone's acceptance criteria.")
            task.lastRecoveryStrategy?.takeIf { it.isNotBlank() }?.let { recovery ->
                appendLine()
                appendLine("RECOVERY STRATEGY PIVOT:")
                appendLine(recovery)
                appendLine("An earlier attempt stalled. You MUST follow this specific recovery angle to break the loop.")
            }
            task.lastError?.takeIf { it.isNotBlank() }?.let { previousFailure ->
                appendLine()
                appendLine("PREVIOUS ATTEMPT DID NOT PASS:")
                appendLine(previousFailure.take(2_000))
                appendLine("Do not repeat the same incomplete approach. Use the preserved prior evidence, diagnose the specific gate failure, and produce a materially improved result.")
            }
            appendLine()
            appendLine("Task acceptance criteria:")
            appendLine(criteriaText)
            
            if (task.capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)) {
                val claimsPrompt = buildStructuredClaimsPrompt(goal.claims)
                appendLine()
                appendLine("Structured claims and their current support status:")
                appendLine(claimsPrompt)
            }

            appendLine()
            appendLine("Prior evidence and checkpoints:")
            appendLine(evidenceContext)
            if (researchBootstrap.context.isNotBlank()) {
                appendLine()
                appendLine("DETERMINISTIC RESEARCH BOOTSTRAP:")
                appendLine("The runtime performed a bounded public-web discovery pass before this model call. Treat these as research leads, preserve the exact URLs, fetch important pages when useful, and corroborate material claims. Do not invent details beyond the supplied snippets or fetched evidence.")
                appendLine(researchBootstrap.context)
            }
            appendLine()
            appendLine("Complete this milestone without asking the user to enable a mode, press an automation button, or approve a low-risk local tool.")
            if (allowInteractiveToolsForCall) {
                appendLine("Select and call available tools whenever they increase correctness. You may make several deterministic, workspace, recipe, or hosted-sandbox tool calls before returning the structured result.")
                if (bootstrapCompletedResearchTools) {
                    appendLine("The deterministic research bootstrap already completed the required distinct searches, full-source reads, and evidence-driven follow-up for this pass. Reuse preserved evidence first before repeating work. You may still use new searches and tools when they address an unresolved evidence gap.")
                }
                when (executionStrategy.profile) {
                    AgentExecutionProfile.FOCUSED_TOOL -> {
                        appendLine("FOCUSED TOOL RECOVERY: the previous response skipped a required local tool. Begin with one relevant deterministic function call before writing the milestone result.")
                        taskToolPlan.preferredFunctionName?.let { functionName ->
                            appendLine("The runtime selected '$functionName' as the best first function for this milestone. Use its real output as evidence; do not call it with invented or irrelevant inputs.")
                        }
                    }
                    AgentExecutionProfile.ANGLE_SWITCH_RECOVERY -> {
                        appendLine("ANGLE SWITCH RECOVERY: Your previous direct approach hit a wall. DO NOT repeat the same logic, keywords, or queries. Pivot to a radically different lateral angle: use community-consensus sources (forums, social threads), forensic clues (inferring from sibling products or related entities), or alternate technical standards. Persistence is required; find a way around the obstacle.")
                    }
                    else -> {}
                }
            } else {
                appendLine("EXECUTION PROFILE: ${executionStrategy.explanation}")
            }
            appendLine("Separate facts, inferences, recommendations, and uncertainty in the claims array.")
            appendLine("A factual claim must cite a source URL returned by research or a preserved evidence ID. Never invent a citation.")
            appendLine("Do not claim any device action, external side effect, permission, file operation, purchase, message, or code installation that is not evidenced here.")
            appendLine("Score each acceptance criterion honestly; unresolved questions must remain explicit.")
            appendLine("Stop using tools when the result is sufficiently grounded.")
            if (task.capability == AgentCapability.SYNTHESIZE && completedSynthesisRecoveries > 0) {
                appendLine("SYNTHESIS GAP RECOVERY: $completedSynthesisRecoveries focused alternate-angle pass(es) were inserted because an earlier synthesis exposed a concrete evidence gap. Integrate their new evidence and method; do not repeat the earlier answer unchanged.")
            }
            when {
                deepResearch -> {
                    appendLine()
                    appendLine("FOCUSED RESEARCH PROTOCOL (${researchRole.name.lowercase()}):")
                    appendLine("1. Work only on this pass's role; use preserved evidence instead of repeating completed passes.")
                    if (executionStrategy.reuseCheckpointSources || bootstrapCompletedResearchTools) {
                        appendLine("2. Use the supplied bootstrap or preserved checkpoint evidence first. You may use additional searches and tools when addressing an unresolved evidence gap. Analyze the preserved query trail, fetched text, exact URLs, and disagreements into the complete work product.")
                    } else {
                        appendLine("2. Run at least ${autonomyPolicy.minimumSearchQueriesPerResearchPass} genuinely distinct search angles. Extract unfamiliar terms, named entities, citations, datasets, and disagreements from what you read, then follow the most informative leads with additional searches.")
                        appendLine("3. Open and analyze at least ${autonomyPolicy.targetFullSourceReadsPerResearchPass} important full pages, reports, datasets, or PDFs when accessible. Short search-result snippets are discovery clues, never sufficient proof for a material conclusion. If provider search returns long query-focused source extracts but exact fetch telemetry is unavailable, corroborate at least ${autonomyPolicy.targetFullSourceReadsPerResearchPass * PROVIDER_EXTRACTS_PER_READ_UNIT} independent substantial extracts and still fetch the most decision-critical pages whenever the route supports it.")
                    }
                    appendLine("4. Preserve at least ${autonomyPolicy.minimumSourcesPerResearchPass} distinct high-quality HTTPS source URLs, including at least one source not used by an earlier pass, and include the query trail in the work product.")
                    appendLine("5. State dates, methodology, scope boundaries, provenance, and uncertainty explicitly. Do not stop merely because the first plausible answer agrees with the emerging conclusion.")
                    when (researchRole) {
                        ResearchPassRole.DISCOVERY ->
                            appendLine("6. Map terminology, candidate sources, important subquestions, and evidence gaps. Follow at least one rabbit-hole lead discovered inside a source. Do not require the later contradiction gate in this discovery pass.")

                        ResearchPassRole.PRIMARY ->
                            appendLine("6. Trace material claims back to official, first-party, government, standards, original-research, direct-dataset, map, or measurement evidence and verify the method behind the value.")

                        ResearchPassRole.CONTRADICTION ->
                            appendLine("6. Deliberately seek counterevidence, limitations, false positives, alternate definitions, boundary effects, and disconfirming findings; say exactly what was searched when none are found.")

                        ResearchPassRole.GAP_CLOSURE ->
                            appendLine("6. Target unresolved questions, stale claims, missing dates, weak diversity, and unsupported conclusions; pursue citations and newly discovered leads until each gap is closed or explicitly bounded.")

                        ResearchPassRole.FORENSIC ->
                            appendLine("6. Perform forensic reconstruction: infer direct answers from surrounding indirect evidence, sibling products, related entities, or historical context when direct results are unavailable.")

                        ResearchPassRole.GENERAL ->
                            appendLine("6. Balance source discovery, primary verification, contradiction hunting, and explicit uncertainty without claiming evidence that was not retrieved.")
                    }
                    if (isFreeOnlyModel(modelId)) {
                        appendLine("7. This free route has no paid subagent, advisor, or Fusion tools. Use public_web_search and public_web_fetch repeatedly; do not wait for or claim unavailable tools.")
                    } else {
                        appendLine("7. Use independent subagents for separable questions. Reserve the adversarial advisor and Fusion for contradiction, gap, or genuinely disputed work.")
                    }
                    appendLine("8. The work_product must contain at least ${ResearchQualityGate.MIN_DEEP_RESEARCH_CONTENT_CHARS} characters of actual analysis; tool summaries and a list of URLs alone do not satisfy this gate.")
                    appendLine("9. Return at least ${ResearchQualityGate.MIN_DEEP_RESEARCH_FACTS} structured factual claims grounded in exact preserved HTTPS URLs or evidence IDs, plus explicit uncertainty where the sources do not resolve a point.")
                    appendLine("10. Return every acceptance criterion exactly once and do not mark a criterion failed when the supplied evidence actually satisfies it; explain any genuinely unresolved criterion precisely.")
                }
                task.capability == AgentCapability.SYNTHESIZE -> {
                    appendLine()
                    appendLine("EVIDENCE SYNTHESIS PROTOCOL:")
                    appendLine("1. Produce the complete user-facing result, not a plan, refusal, grading note, or description of what a synthesis would contain.")
                    appendLine("2. Transform supported findings from the preserved evidence into explicit claims. Restating a supported finding here is synthesis, not invention.")
                    appendLine("3. The work_product must contain at least ${ResearchQualityGate.MIN_SYNTHESIS_CONTENT_CHARS} characters of integrated analysis and the claims array must contain at least ${ResearchQualityGate.MIN_SYNTHESIS_CLAIMS} material claims.")
                    appendLine("4. Every factual claim must cite an exact preserved evidence ID and, when web-backed, its matching exact HTTPS source URL. Never invent or alter a citation.")
                    appendLine("5. Address every acceptance criterion. Reconcile disagreements and state unresolved gaps explicitly instead of using an empty response to avoid a conclusion.")
                    appendLine("6. Match each factual claim's exact entity, model, version, and qualifier to the cited source title and URL path. A multi-source evidence bundle supplies candidate sources; it does not prove that every URL in the bundle supports every claim.")
                    appendLine("7. When a criterion remains partial, name the exact missing record, identifier, dataset, access route, measurement, or deterministic operation in its explanation and unresolved_questions so the runtime can route a focused recovery pass.")
                    if (completedSynthesisRecoveries >= MAX_SYNTHESIS_GAP_RECOVERY_PASSES) {
                        appendLine("8. The bounded alternate-angle recovery budget has completed. If preserved evidence still cannot establish an exact answer, deliver the strongest supported answer, describe the attempted routes and precise boundary, and grade a criterion PASS when that evidence-bounded conclusion fully satisfies the criterion as written. Never invent certainty merely to pass.")
                    }
                }
                task.capability == AgentCapability.CORRECT -> {
                    appendLine()
                    appendLine("VERIFICATION CORRECTION PROTOCOL:")
                    appendLine("1. Correct the listed verification findings using preserved evidence first. Search, fetch, calculate, inspect, or use another tool whenever additional evidence is needed to resolve a finding.")
                    appendLine("2. Remove contradicted or unsupported statements. Qualify genuine uncertainty instead of presenting it as fact.")
                    appendLine("3. Every factual claim in the claims array must include an exact preserved evidence_id and, for web-backed work, its matching exact HTTPS source URL.")
                    appendLine("4. Omit any factual claim that cannot be traced precisely. Preserved evidence remains available even when a claim is excluded from publication.")
                    appendLine("5. The work_product itself must be the complete corrected user-facing result, not a description of what a corrected result includes or a note claiming that findings were addressed.")
                    appendLine("6. The work_product must contain at least ${ResearchQualityGate.MIN_CORRECTION_CONTENT_CHARS} characters and at least ${ResearchQualityGate.MIN_CORRECTION_CLAIMS} material evidence-grounded claims, including one factual claim.")
                    appendLine("7. Address every listed gate finding. Use new searches and tools when they address an unresolved evidence gap. Stop using tools when the result is sufficiently grounded.")
                    appendLine("8. Recheck entity-to-source fit using the preserved source titles and URL paths. Never cite a sibling product, model, version, or page merely because it appears in the same multi-source evidence bundle.")
                    appendLine("9. A finding is resolved when the replacement publication removes the unsupported claim, qualifies it as uncertainty, or supplies precise preserved support. Missing evidence does not force the corrected answer to repeat an unprovable claim. Mark that finding PASS when the offending assertion is no longer published and its consequence is stated honestly.")
                }
                task.capability == AgentCapability.TOOL_CREATE -> {
                    appendLine()
                    appendLine("TOOL FOUNDRY PROTOCOL:")
                    appendLine("Inspect existing recipes first. Create a new recipe only when the workflow is reusable and deterministic.")
                    appendLine("The recipe must use only approved primitives or the fixed invoke_builtin bridge to approved deterministic tools, include representative and edge-case tests, and pass every test before activation.")
                    appendLine("After creation, call the generated recipe tool on a realistic input to prove it works, then describe its limits.")
                }
                task.capability == AgentCapability.TOOL_USE -> {
                    appendLine()
                    appendLine("TOOL EXECUTION PROTOCOL:")
                    appendLine("Use the smallest deterministic tool sequence that can produce or verify the result. Reuse an active recipe tool when one matches.")
                    appendLine("When local primitives are insufficient for code execution, tests, data analysis, document transformation, or reproducible computation, use sandbox_workbench. It runs only in an isolated OpenRouter-hosted container and cannot change the phone or the app.")
                    appendLine("Treat tool output as evidence, inspect it for errors, and do not silently substitute guessed values.")
                }
            }
            appendLine()
            appendLine(stepExecutionShapeContract(task.acceptanceCriteria))
        }

        fun configuredPayload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val role = determineTaskRole(task, modelId)
            val (payload, attribution) = basePayload(
                modelId = modelId,
                systemPrompt = EXECUTOR_SYSTEM_PROMPT,
                userPrompt = prompt,
                reasoningEffort = if (deepResearch && !isFreeOnlyModel(modelId)) "medium" else null,
                role = role,
                selectionReason = "execute_task_${task.capability.wireName}",
                freeOnly = goal.freeOnly
            )
            payload.apply {
                put("temperature", if (task.capability == AgentCapability.SYNTHESIZE) 0.18 else 0.08)
                responseFormat?.let { put("response_format", it) }
                if (allowInteractiveToolsForCall && taskToolPlan.tools.length() > 0) {
                    put("tools", taskToolPlan.tools)
                    put("parallel_tool_calls", task.capability != AgentCapability.TOOL_CREATE)
                    if (executionStrategy.profile == AgentExecutionProfile.FOCUSED_TOOL) {
                        taskToolPlan.preferredFunctionName?.let { functionName ->
                            put("tool_choice", requiredFunctionToolChoice(functionName))
                        }
                    }
                }
            }
            return payload to attribution
        }

        val rawResponse = try {
            if (toolRuntime != null && allowInteractiveToolsForCall) {
                executeStructuredWithToolsFallback(
                    apiKey = apiKey,
                    strict = configuredPayload(jsonSchemaResponseFormat("agent_step_v3", stepSchema(task))),
                    jsonMode = configuredPayload(JSONObject().put("type", "json_object")),
                    plain = configuredPayload(null),
                    generation = generation,
                    onProgress = onProgress,
                    requestContext = requestContext,
                    goal = goal,
                    maxAttempts = maxAttempts
                )
            } else {
                executeStructuredWithFallback(
                    apiKey = apiKey,
                    strict = configuredPayload(jsonSchemaResponseFormat("agent_step_v3", stepSchema(task))),
                    jsonMode = configuredPayload(JSONObject().put("type", "json_object")),
                    plain = configuredPayload(null),
                    generation = generation,
                    requestContext = requestContext,
                    maxAttempts = maxAttempts
                )
            }
        } catch (error: Throwable) {
            if (error is TerminalPersistenceException || error is CancellationException) throw error
            buildResearchBootstrapFailureCheckpoint(error, researchBootstrap)
                ?: throw error.withAgentUsage(researchBootstrap.summary)
        }
        val allowedUrls = baseAllowedUrls + researchBootstrap.sources.map { it.url } + rawResponse.sources.map { it.url }
        val response = rawResponse
            .withResearchBootstrap(researchBootstrap)
            .withRecoveredInlineSources(allowedUrls)
        val parsedResponse = runCatching { parseStepResponse(response, goal, task) }
            .getOrNull()
            ?.let { recoverResearchAssessment(task, it, autonomyPolicy) }
        val repairReason = if (parsedResponse == null) StructureRepairReason.SCHEMA_FAILURE else parsedResponse.needsResearchMetadataRepair(task, goal)
        if (parsedResponse != null && repairReason == null) return parsedResponse

        // A successful provider call can still contain useful work wrapped in
        // malformed or incomplete structured metadata. Repair only its
        // serialization before restarting the milestone; do not repeat
        // research or tool calls, and keep the same deterministic gates.
        val repairResponse = runCatching {
            executeStepStructureRepair(
                apiKey = apiKey,
                modelId = modelId,
                task = task,
                original = response,
                freeOnly = goal.freeOnly,
                generation = generation,
                requestContext = requestContext.forChildOperation(
                    MissionOperation.STEP_STRUCTURE_REPAIR,
                    AgentTaskRole.PRIMARY_REASONING,
                    taskId = task.id,
                ),
            )
        }.getOrNull() ?: return parsedResponse ?: AgentStepResult(
            content = response.content,
            summary = response.summary,
            sources = response.sources,
            completionScore = if (response.content.isBlank()) 0.0 else 0.55,
            acceptanceChecks = task.acceptanceCriteria.map { criterion ->
                AgentAcceptanceCheck(
                    criterionId = criterion.id,
                    status = AgentAcceptanceCheckStatus.NOT_EVALUATED,
                    score = 0.0,
                    explanation = "The model returned unstructured output; the final verifier must evaluate this criterion.",
                )
            },
            unresolvedQuestions = listOf("Structured step metadata was unavailable after a focused serialization-repair attempt."),
            toolExecutions = response.toolExecutions,
            queryFingerprints = response.queryFingerprints,
            rejectedQueries = response.rejectedQueries,
            repairLineage = StructureRepairLineage(
                originalResponseHash = FingerprintUtils.hash("v1:response:" + response.content),
                originalRequestFingerprint = task.progressFingerprint ?: "",
                repairRequestFingerprint = "", // Since we failed to execute
                repairAttemptCount = 1,
                repairReason = repairReason!!,
                repairOutcome = StructureRepairOutcome.FAILED_SCHEMA,
                preRepairContentChars = response.content.length,
                postRepairContentChars = response.content.length,
                preRepairRawClaims = parsedResponse?.claims?.size ?: 0,
                postRepairRawClaims = 0,
                preRepairRetainedClaims = parsedResponse?.claims?.size ?: 0,
                postRepairRetainedClaims = 0,
                preRepairSupportedClaims = 0,
                postRepairSupportedClaims = 0,
            )
        )

        val accountedResponse = response.mergeRepair(repairResponse).withRecoveredInlineSources(allowedUrls)
        val repairedResult = runCatching { parseStepResponse(accountedResponse, goal, task) }
            .getOrNull()
            ?.let { repaired ->
                val preRepairClaims = parsedResponse?.claims ?: emptyList()
                val postRepairClaims = repaired.claims
                
                val preRepairSupported = preRepairClaims.count { claim ->
                    claim.support !in setOf(AgentClaimSupport.UNSUPPORTED, AgentClaimSupport.CONTRADICTED) &&
                    (claim.supportingEvidenceIds.any(String::isNotBlank) || claim.sourceUrls.any { it.trim().startsWith("https://") })
                }
                val postRepairSupported = postRepairClaims.count { claim ->
                    claim.support !in setOf(AgentClaimSupport.UNSUPPORTED, AgentClaimSupport.CONTRADICTED) &&
                    (claim.supportingEvidenceIds.any(String::isNotBlank) || claim.sourceUrls.any { it.trim().startsWith("https://") })
                }
                
                val finalRepairReason = repaired.needsResearchMetadataRepair(task, goal)
                val outcome = if (finalRepairReason == null) StructureRepairOutcome.PASSED else StructureRepairOutcome.FAILED_QUALITY
                
                recoverResearchAssessment(
                    task = task,
                    result = repaired.copy(
                        structuredOutputRepaired = true,
                        repairLineage = StructureRepairLineage(
                            originalResponseHash = FingerprintUtils.hash("v1:response:" + response.content),
                            originalRequestFingerprint = task.progressFingerprint ?: "",
                            repairRequestFingerprint = FingerprintUtils.hash("v1:response:" + repairResponse.content),
                            repairAttemptCount = 1,
                            repairReason = repairReason!!,
                            repairOutcome = outcome,
                            preRepairContentChars = response.content.length,
                            postRepairContentChars = repaired.content.length,
                            preRepairRawClaims = preRepairClaims.size,
                            postRepairRawClaims = postRepairClaims.size,
                            preRepairRetainedClaims = preRepairClaims.size,
                            postRepairRetainedClaims = postRepairClaims.size,
                            preRepairSupportedClaims = preRepairSupported,
                            postRepairSupportedClaims = postRepairSupported,
                        )
                    ),
                    policy = autonomyPolicy,
                    metadataWasRepaired = true,
                )
            }
        if (repairedResult != null) return repairedResult

        val explicitAssessment = recoverExplicitStepAssessment(
            repairContent = repairResponse.content,
            criteria = task.acceptanceCriteria,
        )
        if (explicitAssessment != null) {
            return recoverResearchAssessment(
                task = task,
                result = AgentStepResult(
                    content = response.content,
                    summary = accountedResponse.summary,
                    sources = response.sources,
                    completionScore = explicitAssessment.completionScore,
                    acceptanceChecks = explicitAssessment.checks,
                    claims = emptyList(),
                    unresolvedQuestions = explicitAssessment.unresolvedQuestions,
                    toolExecutions = response.toolExecutions,
                    structuredOutputRepaired = true,
                    queryFingerprints = response.queryFingerprints,
                ),
                policy = autonomyPolicy,
                metadataWasRepaired = true,
            )
        }

        return parsedResponse ?: AgentStepResult(
            content = response.content,
            summary = accountedResponse.summary,
            sources = accountedResponse.sources,
            completionScore = if (response.content.isBlank()) 0.0 else 0.55,
            acceptanceChecks = task.acceptanceCriteria.map { criterion ->
                AgentAcceptanceCheck(
                    criterionId = criterion.id,
                    status = AgentAcceptanceCheckStatus.NOT_EVALUATED,
                    score = 0.0,
                    explanation = "The model returned unstructured output; the final verifier must evaluate this criterion.",
                )
            },
            unresolvedQuestions = listOf("Structured step metadata was unavailable after a focused serialization-repair attempt."),
            toolExecutions = accountedResponse.toolExecutions,
            queryFingerprints = accountedResponse.queryFingerprints,
        )
    }

    private suspend fun executeStepStructureRepair(
        apiKey: String,
        modelId: String,
        task: AgentTask,
        original: RawAgentResponse,
        freeOnly: Boolean = false,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): RawAgentResponse {
        val criteria = task.acceptanceCriteria.joinToString("\n") { criterion ->
            "- ${criterion.id}: ${criterion.description}"
        }.ifBlank { "- No explicit criteria were supplied." }
        val preservedSourceEvidence = providerSourceEvidenceContext(original.sources)
            .ifBlank { "No provider source extracts were preserved." }
        val originalResponseBudget =
            (MAX_STRUCTURE_REPAIR_CHARS - preservedSourceEvidence.length).coerceAtLeast(8_000)
        val prompt = buildString {
            appendLine("Repair and complete the serialization of the supplied milestone response. Do not redo research or call tools.")
            appendLine("The preserved provider extracts below are part of the supplied response. When the assistant text is merely a procedural preamble, synthesize the work product directly from those extracts and attribute every factual claim to its matching URL.")
            appendLine("Return the preserved work and evidence as one object matching the requested schema. Do not add facts, citations, or actions that are absent from the assistant text or provider extracts.")
            appendLine("Grade the listed criteria honestly from that response. Keep unsupported points unresolved.")
            appendLine()
            appendLine("Milestone: ${task.title}")
            appendLine("Acceptance criteria:")
            appendLine(criteria)
            appendLine("BEGIN PRESERVED PROVIDER EVIDENCE")
            appendLine(preservedSourceEvidence)
            appendLine("END PRESERVED PROVIDER EVIDENCE")
            appendLine()
            appendLine(stepRepairShapeContract(task.acceptanceCriteria))
            appendLine()
            appendLine("BEGIN UNSTRUCTURED RESPONSE")
            appendLine(boundedText(original.content, originalResponseBudget))
            appendLine("END UNSTRUCTURED RESPONSE")
        }

        fun repairPayload(user: String, selection: String, responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = modelId,
                systemPrompt = STRUCTURE_REPAIR_SYSTEM_PROMPT,
                userPrompt = user,
                reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = selection,
                freeOnly = freeOnly
            )
            p.put("temperature", 0.0)
            responseFormat?.let { p.put("response_format", it) }
            return p to attr
        }

        return executeStructuredWithFallback(
            apiKey = apiKey,
            strict = repairPayload(prompt, "step_structure_repair", jsonSchemaResponseFormat("agent_step_repair_v1", stepSchema(task))),
            jsonMode = repairPayload("$prompt\nReturn one valid JSON object matching the requested structure and no markdown.", "step_structure_repair_json_mode", JSONObject().put("type", "json_object")),
            plain = repairPayload("$prompt\nReturn one valid JSON object and no markdown or surrounding explanation.", "step_structure_repair_plain", null),
            generation = generation,
            requestContext = requestContext,
        )
    }

    suspend fun verifyGoal(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        requestContext: ProviderRequestContext.Mission,
    ): AgentVerificationResult {
        val generation = requestContext.executionGeneration
        val evidence = buildVerificationEvidencePrompt(selectVerificationEvidence(goal))
        val criteria = goal.acceptanceCriteria.joinToString("\n") { criterion ->
            "- ${criterion.id} (weight ${criterion.weight}): ${criterion.description}"
        }
        val tasks = goal.tasks
            .asSequence()
            .sortedBy { it.order }
            .joinToString("\n") { task ->
                "- ${task.id}: ${task.title}; status=${task.status}; score=${"%.2f".format(task.effectiveProgressScore)}"
            }
        val claims = buildStructuredClaimsPrompt(goal.claims)
        val prompt = buildString {
            appendLine("Independently verify whether the work satisfies the original user request.")
            appendLine("Do not pass the work merely because another model said it was complete.")
            appendLine("Check completeness, internal consistency, unsupported claims, contradictions, source fit, and usability.")
            appendLine("Re-grade every goal acceptance criterion and review every structured claim you can evaluate.")
            appendLine("A proposed reusable concept is allowed only when the pattern is genuinely reusable; it remains proposal-only and must include risks and falsifiable shadow tests.")
            appendLine("The final_answer may organize or restate supported material already present in the supplied evidence. Use searches and tools to independently investigate unresolved, contradictory, stale, or weakly supported claims when necessary.")
            appendLine("Evidence can contain rejected, partial, or superseded material for audit. Every factual statement in final_answer must correspond to an active structured claim that you review as supported with its preserved evidence and exact source URL; otherwise omit it or state the uncertainty without presenting it as fact.")
            appendLine("Write final_answer for the user with ordinary Markdown links to the exact HTTPS sources. Internal claim IDs and evidence IDs are audit references, not user-facing citations, and must never be the only citation shown.")
            if (goal.hasEpistemicallyBoundedConclusion()) {
                appendLine("EPISTEMIC-BOUNDARY REVIEW: the runtime already completed its focused alternate-angle recovery budget. Distinguish a genuine evidence boundary from unfinished work. You may leave a criterion PARTIAL only when the final answer clearly gives the strongest supported conclusion, documents the exact residual uncertainty and attempted routes, and avoids false certainty; citation, support, entity-fit, or missing-deliverable defects are never excused by this rule.")
            }
            appendLine()
            appendLine("Original request:")
            appendLine(boundedText(goal.userRequest, MAX_VERIFIER_REQUEST_CHARS))
            appendLine()
            appendLine("Expected final output:")
            appendLine(boundedText(goal.finalOutputDescription, MAX_FINAL_OUTPUT_DESCRIPTION_CHARS))
            appendLine()
            appendLine("Goal acceptance criteria:")
            appendLine(criteria.ifBlank { "No explicit criteria were supplied." })
            appendLine()
            appendLine("Task state:")
            appendLine(tasks)
            appendLine()
            appendLine("Structured claims:")
            appendLine(claims)
            appendLine()
            appendLine("Evidence and work product:")
            append(evidence.ifBlank { "No evidence was produced." })
        }
        val toolPlan = buildTaskToolPlan(
            task = AgentTask(
                id = "verification",
                capability = AgentCapability.VERIFY,
                title = "Independent verification",
                instructions = "Independently verify the goal.",
                order = 0,
            ),
            modelId = modelId,
            focusedRecovery = false,
            networkAvailable = toolRuntime?.isNetworkAvailable() ?: true,
            credentialsAvailable = AgentOperationalState.areCredentialsAvailable(apiKey),
            publicWebConfigured = toolRuntime?.isPublicWebConfigured() ?: true,
            goalId = goal.id
        )
        
        fun configuredPayload(input: Pair<JSONObject, ProviderResponseAttribution>): Pair<JSONObject, ProviderResponseAttribution> {
            val (payload, attribution) = input
            if (toolPlan.tools.length() > 0) {
                payload.put("tools", toolPlan.tools)
                payload.put("parallel_tool_calls", true)
            }
            return payload to attribution
        }

        val response = executeStructuredWithToolsFallback(
            apiKey = apiKey,
            strict = configuredPayload(basePayload(modelId, VERIFIER_SYSTEM_PROMPT, prompt, role = AgentTaskRole.PRIMARY_REASONING, selectionReason = "verify_goal", freeOnly = goal.freeOnly).let { (p, attr) ->
                p.put("temperature", 0.0)
                p.put("response_format", jsonSchemaResponseFormat("agent_verification_v2", verificationSchema()))
                p to attr
            }),
            jsonMode = configuredPayload(basePayload(
                modelId,
                VERIFIER_SYSTEM_PROMPT,
                "$prompt\nReturn one valid JSON object matching the requested structure and no markdown.",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "verify_goal_json_mode",
                freeOnly = goal.freeOnly
            ).let { (p, attr) ->
                p.put("temperature", 0.0)
                p.put("response_format", JSONObject().put("type", "json_object"))
                p to attr
            }),
            plain = configuredPayload(basePayload(
                modelId,
                VERIFIER_SYSTEM_PROMPT,
                "$prompt\nReturn one valid JSON object and no markdown or surrounding explanation.",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "verify_goal_plain",
                freeOnly = goal.freeOnly
            ).let { (p, attr) ->
                p.put("temperature", 0.0)
                p to attr
            }),
            generation = generation,
            requestContext = requestContext,
            goal = goal,
        )
        val initialParse = runCatching { parseVerification(response, goal) }
        initialParse.getOrNull()?.let { return it }

        // A provider may accept JSON mode yet wrap an otherwise useful verifier
        // result in an incompatible array or prose envelope. Repair only that
        // serialization once; do not spend a correction pass redoing research.
        val repairResponse = runCatching {
            executeVerificationStructureRepair(
                apiKey = apiKey,
                modelId = modelId,
                goal = goal,
                original = response,
                generation = generation,
                requestContext = requestContext.forChildOperation(
                    MissionOperation.VERIFICATION_REPAIR,
                    AgentTaskRole.PRIMARY_REASONING,
                    taskId = null,
                ),
            )
        }.getOrNull()
        val accountedResponse = repairResponse?.let { repaired -> response.mergeRepair(repaired) } ?: response
        val repairedParse = repairResponse?.let {
            runCatching { parseVerification(accountedResponse, goal) }
        }
        repairedParse?.getOrNull()?.let { repaired ->
            return repaired.copy(structuredOutputRepaired = true)
        }

        val parseError = repairedParse?.exceptionOrNull() ?: initialParse.exceptionOrNull()
        return AgentVerificationResult(
            passed = false,
            qualityScore = 0.0,
            summary = "The verification response could not be parsed safely after one serialization-repair attempt.",
            missingRequirements = listOf(
                parseError?.message.orEmpty().ifBlank { "A structured verification result is required." }.take(300),
            ),
            acceptanceChecks = goal.acceptanceCriteria.map { criterion ->
                AgentAcceptanceCheck(
                    criterionId = criterion.id,
                    status = AgentAcceptanceCheckStatus.NOT_EVALUATED,
                    score = 0.0,
                    explanation = "Verification metadata was unavailable.",
                )
            },
            claimReviews = emptyList(),
            correctionInstructions = "Rebuild the final result with explicit support for every factual claim and every acceptance criterion.",
            finalAnswer = goal.evidence.lastOrNull { it.kind == AgentEvidenceKind.MODEL_OUTPUT }?.content.orEmpty(),
            conceptCandidates = emptyList(),
            apiSummary = accountedResponse.summary,
        )
    }

    private suspend fun executeVerificationStructureRepair(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        original: RawAgentResponse,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): RawAgentResponse {
        val criteria = goal.acceptanceCriteria.joinToString("\n") { criterion ->
            "- ${criterion.id}: ${criterion.description}"
        }.ifBlank { "- No explicit criteria were supplied." }
        val claimIds = goal.claims.joinToString("\n") { claim -> "- ${claim.id}" }
            .ifBlank { "- No structured claims were supplied." }
        val prompt = buildString {
            appendLine("Repair only the serialization of the supplied independent-verification response.")
            appendLine("Do not redo research, add facts, strengthen conclusions, or invent claim reviews.")
            appendLine("Preserve the response's actual pass/fail judgment, score, missing requirements, explanations, and final answer.")
            appendLine("Use only the listed criterion IDs and claim IDs. If a required field is absent, choose the conservative failing or empty value.")
            appendLine()
            appendLine("Allowed acceptance-criterion IDs:")
            appendLine(criteria)
            appendLine("Allowed claim IDs:")
            appendLine(claimIds)
            appendLine()
            appendLine("BEGIN UNSTRUCTURED VERIFICATION RESPONSE")
            appendLine(boundedText(original.content, MAX_STRUCTURE_REPAIR_CHARS))
            appendLine("END UNSTRUCTURED VERIFICATION RESPONSE")
        }
        return executeStructuredWithFallback(
            apiKey = apiKey,
            strict = basePayload(modelId, STRUCTURE_REPAIR_SYSTEM_PROMPT, prompt, role = AgentTaskRole.PRIMARY_REASONING, selectionReason = "verification_repair", freeOnly = goal.freeOnly).let { (p, attr) ->
                p.put("temperature", 0.0)
                p.put("response_format", jsonSchemaResponseFormat("agent_verification_repair_v1", verificationSchema()))
                p to attr
            },
            jsonMode = basePayload(
                modelId,
                STRUCTURE_REPAIR_SYSTEM_PROMPT,
                "$prompt\nReturn one valid JSON object matching the requested structure and no markdown.",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "verification_repair_json_mode",
                freeOnly = goal.freeOnly
            ).let { (p, attr) ->
                p.put("temperature", 0.0)
                p.put("response_format", JSONObject().put("type", "json_object"))
                p to attr
            },
            plain = basePayload(
                modelId,
                STRUCTURE_REPAIR_SYSTEM_PROMPT,
                "$prompt\nReturn one valid JSON object and no markdown or surrounding explanation.",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "verification_repair_plain",
                freeOnly = goal.freeOnly
            ).let { (p, attr) ->
                p.put("temperature", 0.0)
                p to attr
            },
            generation = generation,
            requestContext = requestContext,
        )
    }

    private fun parseStepResponse(
        response: RawAgentResponse,
        goal: AgentGoal,
        task: AgentTask,
    ): AgentStepResult {
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Agent milestone")
        require(hasCanonicalStepWireShape(root)) {
            "Agent milestone omitted the canonical structured-output fields or scalar types."
        }
        val checks = parseAcceptanceChecks(root.optJSONArray("acceptance_checks"), task.acceptanceCriteria)
        val weightedCheckScore = weightedCheckScore(task.acceptanceCriteria, checks)
        val reportedScore = root.optDouble("completion_score", weightedCheckScore).coerceIn(0.0, 1.0)
        val completionScore = when {
            task.acceptanceCriteria.isEmpty() -> reportedScore
            else -> minOf(reportedScore, weightedCheckScore)
        }
        val validEvidenceIds = goal.evidence.mapTo(mutableSetOf()) { it.id }
        val sourceBackedEvidenceIds = goal.evidence
            .filter { it.sources.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.id }
        val allowedUrls = buildSet {
            goal.evidence.flatMapTo(this) { evidence -> evidence.sources.map { it.url } }
            response.sources.mapTo(this) { it.url }
        }
        val seenClaimIds = mutableSetOf<String>()
        val claims = responseJsonList(root.optJSONArray("claims")) { raw, index ->
            val requestedId = raw.optString("id").trim()
            val claimId = scopedClaimId(task.id, requestedId, index + 1)
                .let { base -> generateSequence(base) { previous -> "${previous}_x" }.first(seenClaimIds::add) }
            val type = AgentClaimType.fromWireName(raw.optString("type"))
            val evidenceIds = raw.optJSONArray("supporting_evidence_ids").toStringList()
                .filter(validEvidenceIds::contains)
                .distinct()
            val sourceUrls = raw.optJSONArray("source_urls").toStringList()
            .filter { (it.startsWith("https://")) && (it in allowedUrls) }
                .distinct()
            val support = determineClaimSupport(type, evidenceIds, sourceUrls, sourceBackedEvidenceIds)
            AgentClaim(
                id = claimId,
                taskId = task.id,
                text = raw.optString("text").trim().take(MAX_CLAIM_TEXT_CHARS),
                type = type,
                confidence = raw.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                support = support,
                supportingEvidenceIds = evidenceIds,
                sourceUrls = sourceUrls,
            )
        }.filter { it.text.isNotBlank() }
            .let { normalizeDurableClaims(task, it) }

        return AgentStepResult(
            content = root.optString("work_product").trim().ifBlank { response.content },
            summary = response.summary,
            sources = response.sources,
            sourceReads = response.sourceReads,
            completionScore = completionScore,
            acceptanceChecks = checks,
            claims = claims,
            unresolvedQuestions = root.optJSONArray("unresolved_questions").toStringList()
                .map { it.take(500) }
                .take(10),
            toolExecutions = response.toolExecutions,
            queryFingerprints = response.queryFingerprints,
            rejectedQueries = response.rejectedQueries,
        )
    }

    private fun parseVerification(
        response: RawAgentResponse,
        goal: AgentGoal,
    ): AgentVerificationResult {
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Agent verification")
        val requiredScalarFields = listOf("passed", "quality_score", "summary", "correction_instructions", "final_answer")
        val requiredArrayFields = listOf(
            "missing_requirements",
            "acceptance_checks",
            "claim_reviews",
            "concept_candidates",
        )
        require(requiredScalarFields.all { field -> root.has(field) }) {
            "Agent verification omitted required scalar metadata."
        }
        require(requiredArrayFields.all { field -> root.optJSONArray(field) != null }) {
            "Agent verification omitted required array metadata."
        }
        val checks = parseAcceptanceChecks(root.optJSONArray("acceptance_checks"), goal.acceptanceCriteria)
        val validClaimIds = goal.claims.mapTo(mutableSetOf()) { it.id }
        val claimReviews = responseJsonList(root.optJSONArray("claim_reviews")) { raw, _ ->
            AgentClaimReview(
                claimId = raw.optString("claim_id"),
                support = AgentClaimSupport.fromWireName(raw.optString("support")),
                explanation = raw.optString("explanation").trim().take(1_000),
            )
        }.filter { it.claimId in validClaimIds }
        val concepts = responseJsonList(root.optJSONArray("concept_candidates")) { raw, _ ->
            AgentConceptCandidate(
                name = raw.optString("name").trim().take(120),
                definition = raw.optString("definition").trim().take(2_000),
                triggerPattern = raw.optString("trigger_pattern").trim().take(1_000),
                expectedBenefit = raw.optString("expected_benefit").trim().take(1_000),
                risks = raw.optJSONArray("risks").toStringList().map { it.take(500) }.take(8),
                validationTests = raw.optJSONArray("validation_tests").toStringList().map { it.take(500) }.take(8),
            )
        }.filter { it.name.isNotBlank() && it.definition.isNotBlank() }.take(3)
        return AgentVerificationResult(
            passed = root.optBoolean("passed", false),
            qualityScore = root.optDouble("quality_score", 0.0).coerceIn(0.0, 1.0),
            summary = root.optString("summary", "Verification did not provide a summary.").trim().take(2_000),
            missingRequirements = root.optJSONArray("missing_requirements").toStringList().map { it.take(1_000) }.take(20),
            acceptanceChecks = checks,
            claimReviews = claimReviews,
            correctionInstructions = root.optString("correction_instructions")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.take(4_000),
            finalAnswer = root.optString("final_answer").trim().take(MAX_FINAL_ANSWER_CHARS),
            conceptCandidates = concepts,
            apiSummary = response.summary,
        )
    }

    internal fun buildStructuredClaimsPrompt(claims: List<AgentClaim>): String {
        if (claims.isEmpty()) return "No structured claims were produced."
        val lines = claims.map { claim ->
            buildString {
                append("- claim_id=${claim.id}; type=${claim.type.wireName}; current_support=${claim.support.wireName}; confidence=${"%.2f".format(claim.confidence)}; text=${claim.text}")
                if (claim.supportingEvidenceIds.isNotEmpty()) {
                    append("; evidence_ids=${claim.supportingEvidenceIds.joinToString()}")
                }
                if (claim.sourceUrls.isNotEmpty()) {
                    append("; source_urls=${claim.sourceUrls.joinToString()}")
                }
            }.take(MAX_VERIFICATION_CLAIM_LINE_CHARS)
        }
        val selected = mutableListOf<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val remaining = MAX_VERIFICATION_CLAIMS_CHARS - used
            if (remaining <= 0) break
            selected.add(0, line.take(remaining))
            used += minOf(line.length, remaining)
        }
        val omitted = lines.size - selected.size
        return buildString {
            if (omitted > 0) {
                appendLine("[Runtime note: $omitted older claim(s) were omitted from this request to fit the context window; their structured records remain persisted.]")
            }
            append(selected.joinToString("\n"))
        }
    }

    private fun boundedText(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        return value.take(maxChars) + "\n[Runtime note: remaining text omitted to fit the request context window.]"
    }

    private fun buildVerificationEvidencePrompt(evidenceItems: List<AgentEvidence>): String {
        val blocks = evidenceItems
            .asSequence()
            .filter { it.kind != AgentEvidenceKind.SYSTEM_EVENT }
            .map { item ->
                buildString {
                    appendLine("## Evidence ${item.id}: ${item.title}")
                    append(item.content.take(MAX_VERIFICATION_EVIDENCE_CHARS))
                    if (item.sources.isNotEmpty()) {
                        appendLine()
                        appendLine("Sources preserved by the runtime:")
                        item.sources.take(10).forEach { source ->
                            appendLine("- ${source.title.take(MAX_SOURCE_TITLE_CHARS)}: ${source.url.take(MAX_SOURCE_URL_CHARS)}")
                        }
                    }
                }.take(MAX_VERIFICATION_EVIDENCE_CHARS + MAX_VERIFICATION_SOURCE_CHARS)
            }
            .toList()
        if (blocks.isEmpty()) return "No evidence was produced."

        val selected = mutableListOf<String>()
        var used = 0
        for (block in blocks.asReversed()) {
            val remaining = MAX_VERIFICATION_PROMPT_CHARS - used
            if (remaining <= 0) break
            val kept = block.take(remaining)
            selected.add(0, kept)
            used += kept.length
        }
        val omitted = blocks.size - selected.size
        return buildString {
            if (omitted > 0) {
                appendLine("[Runtime note: $omitted older evidence item(s) were omitted from this verifier request to fit the request context window; their metadata remains persisted.]" )
                appendLine()
            }
            append(selected.joinToString("\n\n"))
        }
    }

    private data class TaskToolPlan(
        val tools: JSONArray,
        val preferredFunctionName: String?,
    ) {
        companion object {
            val EMPTY = TaskToolPlan(JSONArray(), null)
        }
    }

    private fun buildTaskToolPlan(
        task: AgentTask,
        modelId: String,
        focusedRecovery: Boolean,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        publicWebConfigured: Boolean = true,
        goalId: String? = null
    ): TaskToolPlan {
        val researchRole = researchPassRole(task)
        val includeAdvanced = researchRole in setOf(ResearchPassRole.CONTRADICTION, ResearchPassRole.GAP_CLOSURE)
        
        val payloadWithAudit = AgentToolRegistry.attachedToolsPayloadWithAudit(
            runtime = toolRuntime,
            networkAvailable = networkAvailable,
            credentialsAvailable = credentialsAvailable,
            publicWebConfigured = publicWebConfigured,
            isFreeOnly = isFreeOnlyModel(modelId),
            includeAdvancedResearchTools = includeAdvanced
        )
        
        val audit = payloadWithAudit.audit
        diagnostics?.info(
            event = "tool_registry_audit",
            component = "agent",
            fields = mapOf(
                "goal_id" to goalId,
                "task_id" to task.id,
                "total_configured" to audit.totalConfigured,
                "total_operational" to audit.operational.size,
                "unavailable_count" to audit.unavailable.size,
                "unavailable_reasons" to JSONObject(audit.unavailable).toString()
            )
        )
        
        val localSelection = if (autonomyPolicy.autoExecuteLocalTools) {
            executionToolSelection(
                task = task,
                definitions = toolRuntime?.definitions().orEmpty(),
                focusedRecovery = focusedRecovery,
            )
        } else {
            FocusedToolSelection(emptyList(), null)
        }

        return TaskToolPlan(
            tools = payloadWithAudit.tools,
            preferredFunctionName = localSelection.preferredToolName,
        )
    }

    /**
     * Converts a model-reasoned, request-specific investigation strategy into
     * deterministic public-web searches and full-source reads. Kotlin controls
     * execution and accounting, but it never invents canned query content.
     */
    private suspend fun prepareResearchBootstrap(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        task: AgentTask,
        priorEvidence: List<AgentEvidence>,
        budget: ResearchTaskBudget,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): ResearchBootstrap {
        val runtime = toolRuntime ?: return ResearchBootstrap.EMPTY
        val sources = linkedMapOf<String, AgentSourceCitation>()
        val verifiedUrls = mutableSetOf<String>()
        val executions = mutableListOf<AgentToolExecution>()
        val fetchedPages = mutableListOf<Pair<AgentSourceCitation, String>>()
        val newSourceReads = mutableListOf<SourceRead>()
        var successfulSearches = 0
        var webFetchRequests = 0
        var discoveredLeads = 0
        var rabbitHoleIterations = 0
        val queryCount = budget.searchQueriesTarget +
            if (task.attemptCount > 0 || !task.lastError.isNullOrBlank()) 1 else 0
            
        val (strategy, strategySummary) = if (!task.activeResearchStrategyJson.isNullOrBlank()) {
            val parsed = runCatching { parseAdaptiveResearchStrategy(task.activeResearchStrategyJson, 2, enforceSemanticDiversity = false) }.getOrNull()
            if (parsed != null) {
                parsed to AgentApiSummary()
            } else {
                val (s, res) = createAdaptiveResearchStrategy(
                    apiKey = apiKey,
                    modelId = AgentRoutingPolicy.guardModel(goal, if (task.attemptCount >= 2 && isFreeOnlyModel(modelId)) ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID else modelId),
                    goal = goal,
                    task = task,
                    priorEvidence = priorEvidence,
                    queryCount = queryCount,
                    freeOnly = goal.freeOnly,
                    generation = generation,
                    requestContext = requestContext.forChildOperation(
                        MissionOperation.ADAPTIVE_RESEARCH_STRATEGY,
                        AgentTaskRole.ECONOMICAL_RESEARCH,
                        taskId = task.id,
                    ),
                )
                store?.updateGoal(goal.id) { current ->
                    current.copy(tasks = current.tasks.map { if (it.id == task.id) it.copy(activeResearchStrategyJson = res.content) else it })
                }
                s to res.summary
            }
        } else {
            val (s, res) = createAdaptiveResearchStrategy(
                apiKey = apiKey,
                modelId = AgentRoutingPolicy.guardModel(goal, if (task.attemptCount >= 2 && isFreeOnlyModel(modelId)) ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID else modelId),
                goal = goal,
                task = task,
                priorEvidence = priorEvidence,
                queryCount = queryCount,
                freeOnly = goal.freeOnly,
                generation = generation,
                requestContext = requestContext.forChildOperation(
                    MissionOperation.ADAPTIVE_RESEARCH_STRATEGY,
                    AgentTaskRole.ECONOMICAL_RESEARCH,
                    taskId = task.id,
                ),
            )
            store?.updateGoal(goal.id) { current ->
                current.copy(tasks = current.tasks.map { if (it.id == task.id) it.copy(activeResearchStrategyJson = res.content) else it })
            }
            s to res.summary
        }
        
        var currentStrategySummary = strategySummary
        val queries = strategy.queries.take(queryCount).toMutableList()
        val researchRole = researchPassRole(task)
        val validationEntities = linkedSetOf<String>().apply {
            queries
                .flatMap { query -> query.intent?.entities.orEmpty() }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase(Locale.US) }
                .take(MAX_SOURCE_VALIDATION_ENTITIES)
                .forEach(::add)
        }
        val priorQueryFingerprints = task.queryFingerprints.toSet()
        val executedQueryFingerprints = linkedSetOf<String>()
        val rejectedQueries = mutableListOf<RejectedResearchQuery>()
        executions += AgentToolExecution(
            toolName = "adaptive_research_strategy",
            summary = buildString {
                appendLine("Interpretation: ${strategy.interpretation}")
                appendLine("Decision target: ${strategy.decisionTarget}")
                appendLine("Ambiguities: ${strategy.scopeAmbiguities.joinToString(" | ")}")
                appendLine("Unknowns: ${strategy.unknowns.joinToString(" | ")}")
                appendLine("Evidence targets: ${strategy.evidenceTargets.joinToString(" | ")}")
                appendLine("Falsifiers: ${strategy.falsifiers.joinToString(" | ")}")
                appendLine("Evidence-driven follow-up rule: ${strategy.followUpRule}")
                queries.forEachIndexed { index, query ->
                    appendLine("Branch ${index + 1}: ${query.purpose} Expected: ${query.expectedEvidence}")
                }
            }.trim().take(MAX_ADAPTIVE_STRATEGY_AUDIT_CHARS),
            succeeded = true,
        )

        val initialDiscoverySources = linkedMapOf<String, AgentSourceCitation>()

        for ((index, queryPlan) in queries.withIndex()) {
            if (index > 0) {
                delay(750) // Reduce burst pressure on public gateways
            }
            val validation = SearchQueryValidator.validate(
                query = queryPlan.query,
                request = goal.userRequest,
                resolvedRequest = goal.resolvedResearchRequest,
                sourceRole = researchRole.name,
                informationNeed = queryPlan.purpose
            )
            
            var currentQuery = when (validation) {
                is SearchQueryValidator.ValidationResult.Valid -> {
                    val fingerprint = validation.canonicalFingerprint
                    if (fingerprint in priorQueryFingerprints || !executedQueryFingerprints.add(fingerprint)) {
                        researchMonitor?.record(
                            category = "research",
                            event = "query_duplicate_rejected",
                            correlationId = task.id,
                            targetSessionId = currentSessionId,
                            fields = mapOf(
                                "query" to validation.executionText,
                                "fingerprint" to fingerprint,
                                "branch" to index,
                                "persisted_duplicate" to (fingerprint in priorQueryFingerprints),
                            ),
                        )
                        continue
                    }
                    validation.executionText
                }
                is SearchQueryValidator.ValidationResult.Rejected -> {
                    val rejectedRecord = RejectedResearchQuery(
                        originalQuery = queryPlan.query,
                        normalizedQuery = queryPlan.query.lowercase(Locale.US).trim(),
                        canonicalFingerprint = "", // Not valid
                        taskId = task.id,
                        reasonCode = validation.reasonCode,
                        reasonDetail = validation.reason,
                        matchedWeakAnchors = validation.matchedWeakAnchors,
                        generation = task.taskGeneration
                    )
                    rejectedQueries.add(rejectedRecord)
                    researchMonitor?.record(
                        category = "research",
                        event = "query_rejected",
                        correlationId = task.id,
                        targetSessionId = currentSessionId,
                        fields = mapOf(
                            "query" to queryPlan.query,
                            "reason" to validation.reason,
                            "reason_code" to validation.reasonCode,
                            "branch" to index,
                        ),
                    )
                    continue
                }
            }
            var currentPage = 1
            val maxPages = if (task.capability == AgentCapability.DEEP_RESEARCH) 3 else 1
            val seenUrlsInBranch = mutableSetOf<String>()

            while (currentPage <= maxPages) {
                if (currentPage > 1) delay(500) // Delay between pages
                val call = OpenRouterToolCall(
                    id = "research_bootstrap_${task.id}_${index}_p$currentPage",
                    name = "public_web_search",
                    argumentsJson = JSONObject().apply {
                        put("query", currentQuery)
                        put("page", currentPage)
                    }.toString(),
                )
                
                diagnostics?.info(
                    event = "search_started",
                    component = "research",
                    fields = mapOf(
                        "goal_id" to goal.id,
                        "task_id" to task.id,
                        "search_id" to call.id
                    )
                )
                
                if (researchMonitor?.status()?.detailedContentCaptureEnabled == true) {
                    diagnostics?.contentPreview(
                        kind = "generated_query",
                        content = currentQuery,
                        goalId = goal.id,
                        taskId = task.id,
                        exchangeId = call.id
                    )
                }

                var searchResult = runCatching { runtime.execute(call, apiKey, modelId, goal) }
                var searchRefinementAttempts = 0
                val maxSearchRefinements = 3

                // Human-like recovery: if a specific branch returns no results or only low-relevance noise,
                // try different angles until one works or budget is exhausted.
                while (
                    (searchResult.isFailure || !isHighRelevanceSearchOutcome(currentQuery, searchResult.getOrThrow())) &&
                    !isExtremelyGenericQuery(currentQuery, goal) &&
                    searchRefinementAttempts < maxSearchRefinements
                ) {
                    searchRefinementAttempts++
                    delay(1000) // Pacing for search refinements
                    val failureReason = if (searchResult.isFailure) {
                        searchResult.exceptionOrNull()?.message ?: "Search execution failed."
                    } else {
                        "Initial results for '$currentQuery' were too generic or irrelevant."
                    }
                    
                    // If regular refinements fail, try forensic reconstruction on the last attempt
                    val refinedAttempt = if (searchRefinementAttempts == maxSearchRefinements) {
                        runCatching {
                            reconstructFailedInformationNeed(
                                apiKey = apiKey,
                                modelId = modelId,
                                goal = goal,
                                task = task,
                                failedQuery = queryPlan.copy(query = currentQuery),
                                error = failureReason,
                                generation = generation,
                                requestContext = requestContext.forChildOperation(
                                    MissionOperation.INFORMATION_NEED_RECONSTRUCTION,
                                    AgentTaskRole.ECONOMICAL_RESEARCH,
                                    taskId = task.id,
                                ),
                            )
                        }
                    } else {
                        runCatching {
                            refineFailedSearchQuery(
                                apiKey = apiKey,
                                modelId = modelId,
                                goal = goal,
                                task = task,
                                failedQuery = queryPlan.copy(query = currentQuery),
                                error = failureReason,
                                generation = generation,
                                requestContext = requestContext.forChildOperation(
                                    MissionOperation.FAILED_QUERY_REFINEMENT,
                                    AgentTaskRole.ECONOMICAL_RESEARCH,
                                    taskId = task.id,
                                ),
                            )
                        }
                    }
                    
                    if (refinedAttempt.isSuccess) {
                        val refinedQuery = refinedAttempt.getOrThrow()
                        val refinedValidation = SearchQueryValidator.validate(
                            query = applyAutomaticDisambiguation(refinedQuery.query, goal),
                            request = goal.userRequest,
                            resolvedRequest = goal.resolvedResearchRequest,
                            sourceRole = researchRole.name,
                            informationNeed = queryPlan.purpose
                        )
                        if (refinedValidation is SearchQueryValidator.ValidationResult.Rejected) {
                            searchResult = Result.failure(
                                IllegalArgumentException("Refined search query failed validation: ${refinedValidation.reason}"),
                            )
                            continue
                        }
                        refinedValidation as SearchQueryValidator.ValidationResult.Valid
                        val refinedFingerprint = refinedValidation.canonicalFingerprint
                        if (refinedFingerprint in priorQueryFingerprints || !executedQueryFingerprints.add(refinedFingerprint)) {
                            researchMonitor?.record(
                                category = "research",
                                event = "query_duplicate_rejected",
                                correlationId = task.id,
                                targetSessionId = currentSessionId,
                                fields = mapOf(
                                    "query" to refinedValidation.executionText,
                                    "fingerprint" to refinedFingerprint,
                                    "branch" to index,
                                    "refinement_attempt" to searchRefinementAttempts,
                                    "persisted_duplicate" to (refinedFingerprint in priorQueryFingerprints),
                                ),
                            )
                            searchResult = Result.failure(
                                IllegalStateException("Refined search query duplicated an already executed strategy."),
                            )
                            continue
                        }
                        currentQuery = refinedValidation.executionText
                        val retryCall = call.copy(
                            id = "${call.id}_retry_$searchRefinementAttempts",
                            argumentsJson = JSONObject().apply {
                                put("query", currentQuery)
                            }.toString()
                        )
                        searchResult = runCatching { runtime.execute(retryCall, apiKey, modelId, goal) }
                        
                        val toolNameLabel: String = if (searchRefinementAttempts == maxSearchRefinements) "forensic_reconstruction" else "search_query_refinement"
                        val summaryPrefix = if (searchRefinementAttempts == maxSearchRefinements) "Forensic reconstruction: inferred indirect path " else "Relentless search: switched angle to "
                        
                        executions += AgentToolExecution(
                            toolName = toolNameLabel,
                            summary = "$summaryPrefix '$currentQuery' after attempt $searchRefinementAttempts failed ($failureReason).".take(600),
                            succeeded = searchResult.isSuccess
                        )
                        researchMonitor?.record(
                            category = "research",
                            event = if (searchRefinementAttempts == maxSearchRefinements) "forensic_reconstruction" else "search_angle_switch",
                            correlationId = task.id,
                            targetSessionId = currentSessionId,
                            fields = mapOf(
                                "attempt" to searchRefinementAttempts,
                                "original_query" to queryPlan.query,
                                "new_query" to currentQuery,
                                "failure_reason" to failureReason,
                                "successful" to searchResult.isSuccess
                            )
                        )
                    } else {
                        break
                    }
                }

                var pageYieldedResults = false
                searchResult.onSuccess { result ->
                    successfulSearches += maxOf(1, result.webSearchRequests)
                    val newSources = parseToolSourceCitations(result.outputJson)
                    
                    diagnostics?.info(
                        event = "search_completed",
                        component = "research",
                        fields = mapOf(
                            "goal_id" to goal.id,
                            "task_id" to task.id,
                            "search_id" to call.id,
                            "source_count" to newSources.size,
                            "web_search_requests" to result.webSearchRequests,
                            "duration_ms" to result.durationMs
                        )
                    )
                    
                    if (researchMonitor?.status()?.detailedContentCaptureEnabled == true) {
                        newSources.forEach { s ->
                            diagnostics?.contentPreview(
                                kind = "search_result_snippet",
                                content = s.excerpt.orEmpty(),
                                goalId = goal.id,
                                taskId = task.id,
                                exchangeId = call.id,
                                extraFields = mapOf("url" to s.url)
                            )
                        }
                    }

                    var addedAnyNew = false
                    if (newSources.isNotEmpty()) {
                        pageYieldedResults = true
                        newSources.forEach { source ->
                            if (seenUrlsInBranch.add(source.url)) {
                                sources.putIfAbsent(source.url, source)
                                initialDiscoverySources.putIfAbsent(source.url, source)
                                addedAnyNew = true
                            }
                        }
                    }
                    if (!addedAnyNew) pageYieldedResults = false // Stop pagination if no new URLs
                    executions += AgentToolExecution(
                        toolName = call.name,
                        summary = "Research bootstrap [$currentQuery - p$currentPage]: ${result.displaySummary}".take(600),
                        succeeded = true,
                    )
                }.onFailure { error ->
                    executions += AgentToolExecution(
                        toolName = call.name,
                        summary = error.toAgentFailureMessage("The bounded public-web discovery pass was unavailable."),
                        succeeded = false,
                    )
                }
                
                // If this page was successful but we want more depth, continue to next page.
                // If it was a total failure even after refinement, stop this query branch.
                if (pageYieldedResults && currentPage < maxPages && isHighRelevancePage(currentQuery, searchResult.getOrNull())) {
                    currentPage++
                    researchMonitor?.record(
                        category = "research",
                        event = "pagination_load",
                        correlationId = task.id,
                        targetSessionId = currentSessionId,
                        fields = mapOf("query" to currentQuery, "page" to currentPage)
                    )
                } else {
                    break
                }
            }
        }

        val fetchCandidates = sources.values
            .sortedWith(
                compareByDescending<AgentSourceCitation> { source ->
                    val text = "${source.title} ${source.url} ${source.excerpt}".lowercase(Locale.US)
                    requestAnchorTokens(goal.userRequest).count { anchor -> text.contains(anchor) } > 0
                }.thenByDescending { source ->
                    val text = "${source.title} ${source.url} ${source.excerpt}".lowercase(Locale.US)
                    computeSourceAuthorityScore(source.url, text)
                }.thenByDescending { source ->
                    val text = "${source.title} ${source.url} ${source.excerpt}".lowercase(Locale.US)
                    requestAnchorTokens(goal.userRequest).count { anchor -> text.contains(anchor) }
                }.thenByDescending { source ->
                    val text = "${source.title} ${source.url} ${source.excerpt}".lowercase(Locale.US)
                    listOf("datasheet", "dataset", "official record", "survey", "report", "manual", "pdf")
                        .count(text::contains)
                }
            )
            .distinctBy { source -> ResearchQualityGate.canonicalSourceUrl(source.url) }
            .take(MAX_RESEARCH_FETCH_CANDIDATES)
        for ((index, source) in fetchCandidates.withIndex()) {
            if (fetchedPages.size >= autonomyPolicy.targetFullSourceReadsPerResearchPass) break
            
            if (goal.blockedSources.any { it.canonicalUrl == source.url && it.failureClass == "PDF_UNSUPPORTED" }) {
                continue
            }
            
            val call = OpenRouterToolCall(
                id = "research_bootstrap_fetch_${task.id}_$index",
                name = "public_web_fetch",
                argumentsJson = JSONObject().put("url", source.url).toString(),
            )
            
            diagnostics?.info(
                event = "source_fetch_started",
                component = "research",
                fields = mapOf(
                    "goal_id" to goal.id,
                    "task_id" to task.id,
                    "source_id" to call.id,
                    "url" to source.url
                )
            )

            val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(source.url)
            val fetchFingerprint = FingerprintUtils.hash(canonicalUrl)
            
            val ticket = requestContext.toTicket(requestContext.acquiredAt) as? TaskExecutionTicket
            if (ticket == null) {
                // Should not happen for a research task
                continue
            }
            
            val claimResult = store?.claimSourceFetchAtomic(ticket, task.id, canonicalUrl, fetchFingerprint)
            
            if (claimResult is SourceFetchClaimResult.ReusedExisting) {
                val existingAttempt = claimResult.attempt
                if (existingAttempt.status == SourceFetchStatus.SOURCE_READ_COMMITTED && existingAttempt.sourceReadId != null) {
                    val existingRead = goal.sourceReads.firstOrNull { it.id == existingAttempt.sourceReadId }
                    if (existingRead != null) {
                        verifiedUrls.add(existingRead.url)
                        val resolvedSource = source.copy(url = existingRead.url)
                        sources.putIfAbsent(existingRead.url, resolvedSource)
                        fetchedPages += resolvedSource to existingRead.content.take(MAX_FETCHED_PAGE_CONTEXT_CHARS)
                        newSourceReads += existingRead
                        executions += AgentToolExecution(
                            toolName = call.name,
                            summary = "Reused durable source read: ${existingRead.url}".take(600),
                            succeeded = true
                        )
                        continue
                    }
                }
            }

            try {
                webFetchRequests += 1
                val result = runtime.execute(call, apiKey, modelId, goal)
                val payload = runCatching { JSONObject(result.outputJson) }.getOrNull()
                val text = payload?.optString("text").orEmpty().trim()
                val contentType = payload?.optString("content_type").orEmpty().ifBlank { "text/html" }
                val resolvedUrl = payload?.optString("url").orEmpty().ifBlank { source.url }
                val validation = validateSourceRead(
                    url = resolvedUrl,
                    httpCode = 200,
                    content = text,
                    contentType = contentType,
                    requiredRole = researchPassRole(task).name,
                    targetEntities = validationEntities.toList(),
                )
                
                diagnostics?.info(
                    event = "source_fetch_completed",
                    component = "research",
                    fields = mapOf(
                        "goal_id" to goal.id,
                        "task_id" to task.id,
                        "source_id" to call.id,
                        "url" to resolvedUrl,
                        "http_status" to 200,
                        "byte_count" to text.toByteArray().size,
                        "content_type" to contentType
                    )
                )

                if (!validation.isValid) {
                    val reason = validation.rejectionReason?.name ?: "UNKNOWN_REJECTION"
                    
                    diagnostics?.warning(
                        event = "source_read_rejected",
                        component = "research",
                        fields = mapOf(
                            "goal_id" to goal.id,
                            "task_id" to task.id,
                            "source_id" to call.id,
                            "url" to resolvedUrl,
                            "reason_code" to reason
                        )
                    )
                    
                    if (researchMonitor?.status()?.detailedContentCaptureEnabled == true) {
                        diagnostics?.contentPreview(
                            kind = "source_extract",
                            content = text,
                            goalId = goal.id,
                            taskId = task.id,
                            exchangeId = call.id,
                            extraFields = mapOf("url" to resolvedUrl, "rejected" to true, "reason" to reason)
                        )
                    }
                    continue
                }

                verifiedUrls.add(resolvedUrl)
                diagnostics?.info(
                    event = "source_read_accepted",
                    component = "research",
                    fields = mapOf(
                        "goal_id" to goal.id,
                        "task_id" to task.id,
                        "source_id" to call.id,
                        "url" to resolvedUrl,
                        "authority_score" to validation.authorityScore
                    )
                )
                
                if (researchMonitor?.status()?.detailedContentCaptureEnabled == true) {
                    diagnostics?.contentPreview(
                        kind = "source_extract",
                        content = text,
                        goalId = goal.id,
                        taskId = task.id,
                        exchangeId = call.id,
                        extraFields = mapOf("url" to resolvedUrl)
                    )
                }

                payload?.optJSONArray("discovered_leads")?.let { leads ->
                    for (i in 0 until leads.length()) {
                        val lead = leads.optJSONObject(i) ?: continue
                        val url = lead.optString("url")
                        if (url.isNotBlank() && sources.putIfAbsent(
                                url,
                                AgentSourceCitation(
                                    title = lead.optString("title").ifBlank { sourceTitle(url) },
                                    url = url,
                                    excerpt = "Lead discovered in ${source.url}",
                                ),
                            ) == null
                        ) {
                            discoveredLeads += 1
                        }
                    }
                }

                parseToolSourceCitations(result.outputJson).forEach { fetchedSource ->
                    sources.putIfAbsent(fetchedSource.url, fetchedSource)
                }
                val resolvedSource = source.copy(url = resolvedUrl)
                sources.putIfAbsent(resolvedUrl, resolvedSource)
                fetchedPages += resolvedSource to text.take(MAX_FETCHED_PAGE_CONTEXT_CHARS)
                
                val sourceRead = SourceRead(
                    id = java.util.UUID.randomUUID().toString(),
                    url = resolvedUrl,
                    canonicalUrl = ResearchQualityGate.canonicalSourceUrl(resolvedUrl),
                    httpCode = 200,
                    contentType = contentType,
                    content = text,
                    sourceRole = researchRole.name,
                    authorityScore = validation.authorityScore,
                    provenance = SourceReadProvenance.VERIFIED_FETCH,
                )
                
                val toolExec = AgentToolExecution(
                    toolName = call.name,
                    summary = "Research full-source read [accepted, authority=${validation.authorityScore}]: $resolvedUrl — ${result.displaySummary}".take(600),
                    succeeded = true,
                )
                
                if (claimResult is SourceFetchClaimResult.Claimed && store != null) {
                    val recordResult = store.commitSourceReadAtomic(ticket, claimResult.attempt.id, sourceRead, toolExec)
                    when (recordResult) {
                        is RecordSourceReadResult.Persisted -> {
                            newSourceReads += recordResult.sourceRead
                            executions += toolExec
                        }
                        is RecordSourceReadResult.ReusedExisting -> {
                            newSourceReads += recordResult.sourceRead
                            executions += toolExec
                        }
                        else -> {
                            newSourceReads += sourceRead
                            executions += toolExec
                        }
                    }
                } else {
                    newSourceReads += sourceRead
                    executions += toolExec
                }
            } catch (error: Throwable) {
                if (error is com.david.openassistant.domain.tools.PdfUnsupportedException) {
                    store?.updateGoal(goal.id) { current ->
                        if (current.blockedSources.any { it.canonicalUrl == source.url }) {
                            current
                        } else {
                            val record = BlockedSourceRecord(
                                canonicalDocumentId = null,
                                canonicalUrl = source.url,
                                routeKind = "PDF",
                                failureClass = "PDF_UNSUPPORTED",
                                sourceTaskId = task.id
                            )
                            current.copy(blockedSources = current.blockedSources + record)
                        }
                    }
                }
                executions += AgentToolExecution(
                    toolName = call.name,
                    summary = (
                        "${source.url} — " +
                            error.toAgentFailureMessage("A candidate full source could not be read.")
                        ).take(600),
                    succeeded = false,
                )
            }
        }

        // Human-like researchers don't stop at one discovery pass.
        // Allow multiple iterative rabbit-hole steps if new leads are found.
        var rabbitHoleIterationsRun = 0
        val maxRabbitHoleIterations = if (task.capability == AgentCapability.DEEP_RESEARCH) {
            budget.maxRabbitHoleIterations
        } else {
            minOf(4, budget.maxRabbitHoleIterations)
        }

        while (sources.isNotEmpty() && rabbitHoleIterationsRun < maxRabbitHoleIterations) {
            val passSources = sources.values.toList()
            val (followUp, followUpSummary) = try {
                createEvidenceDrivenFollowUpQuery(
                    apiKey = apiKey,
                    modelId = AgentRoutingPolicy.guardModel(goal, if (rabbitHoleIterationsRun >= 3 && isFreeOnlyModel(modelId)) ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID else modelId),
                    goal = goal,
                    task = task,
                    strategy = strategy,
                    priorQueries = queries,
                    sources = passSources,
                    fetchedPages = fetchedPages,
                    generation = generation,
                    requestContext = requestContext.forChildOperation(
                        MissionOperation.EVIDENCE_FOLLOW_UP,
                        AgentTaskRole.ECONOMICAL_RESEARCH,
                        taskId = task.id,
                    ),
                )
            } catch (error: Throwable) {
                executions += AgentToolExecution(
                    toolName = "evidence_driven_follow_up_strategy",
                    summary = error.toAgentFailureMessage("No valid evidence-driven follow-up query was produced.").take(600),
                    succeeded = false,
                )
                break
            }

            currentStrategySummary = currentStrategySummary.merge(followUpSummary)
            val followUpValidation = SearchQueryValidator.validate(
                query = applyAutomaticDisambiguation(followUp.query, goal),
                request = goal.userRequest,
                resolvedRequest = goal.resolvedResearchRequest,
                sourceRole = researchRole.name,
                informationNeed = "evidence_driven_follow_up"
            )
            if (followUpValidation is SearchQueryValidator.ValidationResult.Rejected) {
                val rejectedRecord = RejectedResearchQuery(
                    originalQuery = followUp.query,
                    normalizedQuery = followUp.query.lowercase(Locale.US).trim(),
                    canonicalFingerprint = "",
                    taskId = task.id,
                    reasonCode = followUpValidation.reasonCode,
                    reasonDetail = followUpValidation.reason,
                    matchedWeakAnchors = followUpValidation.matchedWeakAnchors,
                    generation = task.taskGeneration
                )
                rejectedQueries.add(rejectedRecord)
                executions += AgentToolExecution(
                    toolName = "evidence_driven_follow_up_strategy",
                    summary = "Rejected rabbit-hole query [${followUpValidation.reasonCode}]: ${followUpValidation.reason}".take(600),
                    succeeded = false,
                )
                researchMonitor?.record(
                    category = "research",
                    event = "query_rejected",
                    correlationId = task.id,
                    targetSessionId = currentSessionId,
                    fields = mapOf(
                        "query" to followUp.query,
                        "reason" to followUpValidation.reason,
                        "reason_code" to followUpValidation.reasonCode,
                        "rabbit_hole_iteration" to (rabbitHoleIterationsRun + 1),
                    ),
                )
                break
            }
            followUpValidation as SearchQueryValidator.ValidationResult.Valid
            val followUpFingerprint = followUpValidation.canonicalFingerprint
            if (followUpFingerprint in priorQueryFingerprints || !executedQueryFingerprints.add(followUpFingerprint)) {
                researchMonitor?.record(
                    category = "research",
                    event = "query_duplicate_rejected",
                    correlationId = task.id,
                    targetSessionId = currentSessionId,
                    fields = mapOf(
                        "query" to followUpValidation.executionText,
                        "fingerprint" to followUpFingerprint,
                        "rabbit_hole_iteration" to (rabbitHoleIterationsRun + 1),
                        "persisted_duplicate" to (followUpFingerprint in priorQueryFingerprints),
                    ),
                )
                executions += AgentToolExecution(
                    toolName = "evidence_driven_follow_up_strategy",
                    summary = "Rejected duplicate rabbit-hole query: ${followUpValidation.executionText}".take(600),
                    succeeded = false,
                )
                break
            }
            val validatedFollowUp = followUp.copy(query = followUpValidation.executionText)
            validatedFollowUp.intent?.entities.orEmpty()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot { candidate -> validationEntities.any { it.equals(candidate, ignoreCase = true) } }
                .take((MAX_SOURCE_VALIDATION_ENTITIES - validationEntities.size).coerceAtLeast(0))
                .forEach(validationEntities::add)

            rabbitHoleIterationsRun++
            rabbitHoleIterations = rabbitHoleIterationsRun
            queries += validatedFollowUp
            executions += AgentToolExecution(
                toolName = "evidence_driven_follow_up_strategy",
                summary = "Rabbit-hole branch [$rabbitHoleIterations]: ${validatedFollowUp.query} — ${validatedFollowUp.purpose}".take(800),
                succeeded = true,
            )
            researchMonitor?.record(
                category = "research",
                event = "rabbit_hole_iteration",
                correlationId = task.id,
                targetSessionId = currentSessionId,
                fields = mapOf(
                    "iteration" to rabbitHoleIterations,
                    "max_iterations" to maxRabbitHoleIterations,
                    "follow_up_query" to validatedFollowUp.query,
                    "purpose" to validatedFollowUp.purpose,
                    "depends_on" to validatedFollowUp.dependsOnDiscovery
                )
            )

            val knownUrlsBeforeFollowUp = sources.keys.toSet()
            val searchCall = OpenRouterToolCall(
                id = "research_bootstrap_follow_up_${task.id}_$rabbitHoleIterationsRun",
                name = "public_web_search",
                argumentsJson = JSONObject().put("query", validatedFollowUp.query).toString(),
            )

            var foundNewSources = false
            runCatching { runtime.execute(searchCall, apiKey, modelId, goal) }
                .onSuccess { result ->
                    successfulSearches += maxOf(1, result.webSearchRequests)
                    val discoveredSources = parseToolSourceCitations(result.outputJson)
                    discoveredSources.forEach { source ->
                        if (sources.putIfAbsent(source.url, source) == null) {
                            foundNewSources = true
                        }
                    }
                    executions += AgentToolExecution(
                        toolName = searchCall.name,
                        summary = "Rabbit-hole search [$rabbitHoleIterationsRun]: ${result.displaySummary}".take(600),
                        succeeded = true,
                    )

                    val alreadyFetched = fetchedPages.mapTo(mutableSetOf()) { it.first.url }
                    val followUpSource = discoveredSources.firstOrNull { source ->
                        source.url !in knownUrlsBeforeFollowUp && 
                        source.url !in alreadyFetched &&
                        goal.blockedSources.none { it.canonicalUrl == source.url && it.failureClass == "PDF_UNSUPPORTED" }
                    } ?: discoveredSources.firstOrNull { source ->
                        source.url !in alreadyFetched &&
                        goal.blockedSources.none { it.canonicalUrl == source.url && it.failureClass == "PDF_UNSUPPORTED" }
                    }

                    if (followUpSource != null) {
                        val fetchCall = OpenRouterToolCall(
                            id = "research_bootstrap_follow_up_fetch_${task.id}_$rabbitHoleIterationsRun",
                            name = "public_web_fetch",
                            argumentsJson = JSONObject().put("url", followUpSource.url).toString(),
                        )
                        
                        val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(followUpSource.url)
                        val fetchFingerprint = FingerprintUtils.hash(canonicalUrl)
                        val ticket = requestContext.toTicket(requestContext.acquiredAt) as? TaskExecutionTicket
                        
                        val claimResult = if (ticket != null) store?.claimSourceFetchAtomic(ticket, task.id, canonicalUrl, fetchFingerprint) else null
                        
                        if (claimResult is SourceFetchClaimResult.ReusedExisting) {
                            val existingAttempt = claimResult.attempt
                            if (existingAttempt.status == SourceFetchStatus.SOURCE_READ_COMMITTED && existingAttempt.sourceReadId != null) {
                                val existingRead = goal.sourceReads.firstOrNull { it.id == existingAttempt.sourceReadId }
                                if (existingRead != null) {
                                    verifiedUrls.add(existingRead.url)
                                    val resolvedSource = followUpSource.copy(url = existingRead.url)
                                    sources.putIfAbsent(existingRead.url, resolvedSource)
                                    fetchedPages += resolvedSource to existingRead.content.take(MAX_FETCHED_PAGE_CONTEXT_CHARS)
                                    newSourceReads += existingRead
                                    executions += AgentToolExecution(
                                        toolName = fetchCall.name,
                                        summary = "Reused durable rabbit-hole read: ${existingRead.url}".take(600),
                                        succeeded = true
                                    )
                                    return@onSuccess
                                }
                            }
                        }

                        webFetchRequests += 1
                        runCatching { runtime.execute(fetchCall, apiKey, modelId, goal) }
                            .onSuccess { fetchResult ->
                                val payload = runCatching { JSONObject(fetchResult.outputJson) }.getOrNull()
                                val text = payload?.optString("text").orEmpty().trim()
                                val contentType = payload?.optString("content_type").orEmpty().ifBlank { "text/html" }
                                val resolvedUrl = payload?.optString("url").orEmpty().ifBlank { followUpSource.url }
                                val validation = validateSourceRead(
                                    url = resolvedUrl,
                                    httpCode = 200,
                                    content = text,
                                    contentType = contentType,
                                    requiredRole = researchPassRole(task).name,
                                    targetEntities = validationEntities.toList(),
                                )
                                if (!validation.isValid) {
                                    val reason = validation.rejectionReason?.name ?: "UNKNOWN_REJECTION"
                                    executions += AgentToolExecution(
                                        toolName = fetchCall.name,
                                        summary = "Rejected rabbit-hole read [$reason]: ${followUpSource.url}".take(600),
                                        succeeded = false,
                                    )
                                    researchMonitor?.record(
                                        category = "research",
                                        event = "source_read_rejected",
                                        correlationId = task.id,
                                        targetSessionId = currentSessionId,
                                        fields = mapOf(
                                            "url" to resolvedUrl,
                                            "reason" to reason,
                                            "rabbit_hole_iteration" to rabbitHoleIterationsRun,
                                        ),
                                    )
                                    return@onSuccess
                                }

                                verifiedUrls.add(resolvedUrl)
                                payload?.optJSONArray("discovered_leads")?.let { leads ->
                                    for (i in 0 until leads.length()) {
                                        val lead = leads.optJSONObject(i) ?: continue
                                        val url = lead.optString("url")
                                        if (url.isNotBlank() && sources.putIfAbsent(url, AgentSourceCitation(lead.optString("title"), url)) == null) {
                                            foundNewSources = true
                                            discoveredLeads += 1
                                        }
                                    }
                                }

                                parseToolSourceCitations(fetchResult.outputJson).forEach { source ->
                                    sources.putIfAbsent(source.url, source)
                                }
                                val resolvedSource = followUpSource.copy(url = resolvedUrl)
                                sources.putIfAbsent(resolvedUrl, resolvedSource)
                                fetchedPages += resolvedSource to text.take(MAX_FETCHED_PAGE_CONTEXT_CHARS)
                                
                                val sourceRead = SourceRead(
                                    id = java.util.UUID.randomUUID().toString(),
                                    url = resolvedUrl,
                                    canonicalUrl = ResearchQualityGate.canonicalSourceUrl(resolvedUrl),
                                    httpCode = 200,
                                    contentType = contentType,
                                    content = text,
                                    sourceRole = researchRole.name,
                                    authorityScore = validation.authorityScore,
                                    provenance = SourceReadProvenance.VERIFIED_FETCH,
                                )
                                
                                val toolExec = AgentToolExecution(
                                    toolName = fetchCall.name,
                                    summary = "Rabbit-hole read [$rabbitHoleIterationsRun, accepted, authority=${validation.authorityScore}]: $resolvedUrl — ${fetchResult.displaySummary}".take(600),
                                    succeeded = true,
                                )
                                
                                if (claimResult is SourceFetchClaimResult.Claimed && store != null && ticket != null) {
                                    val recordResult = store.commitSourceReadAtomic(ticket, claimResult.attempt.id, sourceRead, toolExec)
                                    when (recordResult) {
                                        is RecordSourceReadResult.Persisted -> {
                                            newSourceReads += recordResult.sourceRead
                                            executions += toolExec
                                        }
                                        is RecordSourceReadResult.ReusedExisting -> {
                                            newSourceReads += recordResult.sourceRead
                                            executions += toolExec
                                        }
                                        else -> {
                                            newSourceReads += sourceRead
                                            executions += toolExec
                                        }
                                    }
                                } else {
                                    newSourceReads += sourceRead
                                    executions += toolExec
                                }
                            }
                            .onFailure { error ->
                                if (error is com.david.openassistant.domain.tools.PdfUnsupportedException) {
                                    store?.updateGoal(goal.id) { current ->
                                        if (current.blockedSources.any { it.canonicalUrl == followUpSource.url }) {
                                            current
                                        } else {
                                            val record = BlockedSourceRecord(
                                                canonicalDocumentId = null,
                                                canonicalUrl = followUpSource.url,
                                                routeKind = "PDF",
                                                failureClass = "PDF_UNSUPPORTED",
                                                sourceTaskId = task.id
                                            )
                                            current.copy(blockedSources = current.blockedSources + record)
                                        }
                                    }
                                }
                                executions += AgentToolExecution(
                                    toolName = fetchCall.name,
                                    summary = (
                                        "${followUpSource.url} — " +
                                            error.toAgentFailureMessage("The rabbit-hole source could not be read.")
                                        ).take(600),
                                    succeeded = false,
                                )
                            }
                    }
                }
                .onFailure { error ->
                    executions += AgentToolExecution(
                        toolName = searchCall.name,
                        summary = error.toAgentFailureMessage("The evidence-driven rabbit-hole search was unavailable.").take(600),
                        succeeded = false,
                    )
                }

            // If this rabbit-hole iteration found no new leads at all, stop exploring this branch.
            if (!foundNewSources && rabbitHoleIterationsRun > 1) break
        }

        val context = buildString {
            appendLine("Request-specific investigation model (generated from this request and durable evidence; not a canned topic template):")
            appendLine("Interpretation: ${strategy.interpretation}")
            appendLine("Decision target: ${strategy.decisionTarget}")
            appendLine("Scope and ambiguities:")
            strategy.scopeAmbiguities.forEach { appendLine("- $it") }
            appendLine("Material unknowns:")
            strategy.unknowns.forEach { appendLine("- $it") }
            appendLine("Evidence targets:")
            strategy.evidenceTargets.forEach { appendLine("- $it") }
            appendLine("Potential falsifiers or answer-changing findings:")
            strategy.falsifiers.forEach { appendLine("- $it") }
            appendLine("Follow-up rule: ${strategy.followUpRule}")
            appendLine()
            appendLine("Search query trail (${queries.size} independently reasoned information needs):")
            queries.forEach { query ->
                appendLine("- QUERY: ${query.query}")
                appendLine("  PURPOSE: ${query.purpose}")
                appendLine("  EXPECTED EVIDENCE: ${query.expectedEvidence}")
                query.dependsOnDiscovery?.let { appendLine("  DISCOVERY DEPENDENCY: $it") }
            }
            appendLine()
            appendLine("Discovered source leads:")
            sources.values.forEach { source ->
                append("- ")
                append(source.title)
                append("\n  ")
                append(source.url)
                source.excerpt?.takeIf(String::isNotBlank)?.let { excerpt ->
                    append("\n  Snippet: ")
                    append(excerpt.replace(Regex("\\s+"), " ").trim())
                }
                appendLine()
            }
            if (fetchedPages.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Full-source reads (analyze these texts; snippets alone are not proof):")
                fetchedPages.forEach { (source, text) ->
                    appendLine()
                    appendLine("SOURCE: ${source.title}")
                    appendLine("URL: ${source.url}")
                    appendLine(text)
                }
            }
        }.take(MAX_RESEARCH_BOOTSTRAP_CHARS)

        return ResearchBootstrap(
            context = context,
            sources = sources.values.take(MAX_SOURCE_CITATIONS).toList(),
            executions = executions,
            summary = currentStrategySummary.merge(
                AgentApiSummary(
                    webSearchRequests = successfulSearches.takeIf { it > 0 },
                    webFetchRequests = webFetchRequests.takeIf { it > 0 },
                    discoveredLeads = discoveredLeads.takeIf { it > 0 },
                    rabbitHoleIterations = rabbitHoleIterations.takeIf { it > 0 },
                ),
            ),
            queryFingerprints = executedQueryFingerprints.toList(),
            rejectedQueries = rejectedQueries,
            verifiedUrls = verifiedUrls,
            sourceReads = newSourceReads.toList(),
        )
    }

    /**
     * A retry with a strong durable checkpoint should analyze that checkpoint,
     * not spend another round rediscovering the same URLs. Reattaching these
     * already-preserved citations keeps the normal source gates exact while
     * avoiding duplicate searches.
     */
    private fun preservedResearchBootstrap(
        evidence: List<AgentEvidence>,
        taskId: String,
    ): ResearchBootstrap {
        val currentTaskEvidence = evidence.filter { it.taskId == taskId }
        val sources = currentTaskEvidence
            .flatMap { it.sources }
            .distinctBy { it.url }
            .take(MAX_SOURCE_CITATIONS)
        if (sources.isEmpty()) return ResearchBootstrap.EMPTY
        val context = buildString {
            appendLine("Durable checkpoint source set (reuse; do not repeat discovery):")
            sources.forEach { source ->
                append("- ")
                append(source.title)
                append("\n  ")
                appendLine(source.url)
                source.excerpt?.takeIf(String::isNotBlank)?.let { excerpt ->
                    append("  Preserved snippet: ")
                    appendLine(excerpt.replace(Regex("\\s+"), " ").trim())
                }
            }
        }.take(MAX_RESEARCH_BOOTSTRAP_CHARS)
        val preservedToolExecutions = recoverResearchToolAudit(currentTaskEvidence, taskId)
        return ResearchBootstrap(
            context = context,
            sources = sources,
            // Rehydrate real prior proof for deterministic gates. The summary
            // remains empty because this retry performed no new searches.
            executions = preservedToolExecutions,
            summary = AgentApiSummary(),
        )
    }

    private suspend fun createAdaptiveResearchStrategy(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        task: AgentTask,
        priorEvidence: List<AgentEvidence>,
        queryCount: Int,
        freeOnly: Boolean = false,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): Pair<AdaptiveResearchStrategy, RawAgentResponse> {
        val role = researchPassRole(task)
        val durableContext = priorEvidence
            .takeLast(MAX_ADAPTIVE_STRATEGY_EVIDENCE_ITEMS)
            .joinToString("\n\n") { evidence ->
                buildString {
                    appendLine("Evidence ${evidence.id}: ${evidence.title}")
                    appendLine(evidence.content.take(MAX_ADAPTIVE_STRATEGY_EVIDENCE_CHARS_PER_ITEM))
                    evidence.sources.take(MAX_PRIOR_RESEARCH_LEADS).forEach { source ->
                        appendLine("Source lead: ${source.title} — ${source.url}")
                    }
                }
            }
            .ifBlank { "No earlier evidence exists; derive the investigation structure directly from the request." }
        val prompt = buildString {
            appendLine("Build a new investigation model specifically for the request below and the current research role. Do not retrieve facts and do not answer the question yet.")
            appendLine("Reason about the request in its own domain. Identify the actual decision target, operational definitions, hidden ambiguities, material unknowns, evidence forms that could resolve them, and findings that would falsify or materially change the leading interpretation.")
            appendLine("Do not use a reusable list of generic modifiers or copy a stock product, location, scientific, legal, or technical research pattern. Each query must target a different request-specific information need and say why that evidence matters.")
            appendLine("Resolve time-sensitive entity words such as current, incumbent, latest, today, and now before branching. When the durable objective or evidence already names the resolved person, organization, product, law, version, or officeholder, anchor subsequent queries to that exact entity and date. Do not drift into an archived predecessor, sibling product, or obsolete version unless comparison is an explicit evidence need.")
            appendLine("For a time-sensitive identity question, the first branch must verify the incumbent or current entity from an authoritative dated source; later branches must carry that verified identity forward instead of searching only generic role words.")
            appendLine("Use the current role only as an epistemic quality lens, not as a content template: ${role.name.lowercase(Locale.US)}.")
            appendLine("Generate at least $queryCount executable web-search queries. Later queries may depend on terminology, entities, citations, datasets, discrepancies, or gaps found in earlier evidence; state that dependency explicitly. Do not merely paraphrase the same query.")
            appendLine("When a branch must search one or more named websites, use explicit site:example.org constraints. The local search runtime enforces site: hosts on returned URLs; writing a bare domain name does not constrain results.")
            appendLine()
            appendLine("Original request:")
            appendLine(boundedText(goal.userRequest, MAX_EXECUTOR_REQUEST_CHARS))
            appendLine()
            appendLine("Mission objective:")
            appendLine(boundedText(goal.objective, MAX_OBJECTIVE_CHARS))
            appendLine()
            appendLine("Current request-specific milestone:")
            appendLine(task.title)
            appendLine(task.instructions)
            appendLine()
            appendLine("Durable evidence and discovered leads from earlier work:")
            appendLine(durableContext)
            task.lastError?.takeIf(String::isNotBlank)?.let { failure ->
                appendLine()
                appendLine("Previous attempt's precise deficiency:")
                appendLine(failure.take(2_000))
            }
        }

        fun payload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = modelId,
                systemPrompt = RESEARCH_STRATEGY_SYSTEM_PROMPT,
                userPrompt = prompt,
                reasoningEffort = if (isFreeOnlyModel(modelId)) null else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "research_strategy",
                freeOnly = freeOnly
            )
            p.apply {
                put("temperature", 0.12)
                responseFormat?.let { put("response_format", it) }
            }
            return p to attr
        }

        val firstResponse = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = payload(jsonSchemaResponseFormat("adaptive_research_strategy_v1", researchStrategySchema(queryCount))),
            jsonMode = payload(JSONObject().put("type", "json_object")),
            plain = payload(null),
            generation = generation,
            requestContext = requestContext,
        )
        val firstStrategy = runCatching {
            parseAdaptiveResearchStrategy(firstResponse.content, queryCount)
        }.getOrNull()
        if (
            firstStrategy != null &&
            adaptiveResearchStrategyAnchorsRequest(firstStrategy, goal.userRequest, goal.objectiveContract?.strongAnchors.orEmpty())
        ) {
            return firstStrategy to firstResponse
        }

        val repaired = executeResearchStrategyRefinement(
            apiKey = apiKey,
            modelId = modelId,
            originalPrompt = prompt,
            rejectedStrategy = firstResponse.content,
            queryCount = queryCount,
            freeOnly = goal.freeOnly,
            generation = generation,
            requestContext = requestContext.forChildOperation(
                MissionOperation.RESEARCH_STRATEGY_REFINEMENT,
                AgentTaskRole.ECONOMICAL_RESEARCH,
                taskId = task.id,
            ),
        )
        val combinedSummary = firstResponse.summary.merge(repaired.summary)
        val strictRepair = runCatching { parseAdaptiveResearchStrategy(repaired.content, queryCount) }
        val strategy = strictRepair.getOrNull() ?: runCatching {
            // Two provider calls have already tried to build distinct semantic
            // branches. If only the overlap heuristic still objects, preserve
            // the model's structurally complete request-specific strategy and
            // let full-source reading plus the evidence-driven follow-up stage
            // create the next angle. Rejecting all work here caused a durable
            // mission to spend a whole retry without searching anything.
            parseAdaptiveResearchStrategy(
                content = repaired.content,
                minimumQueries = queryCount,
                enforceSemanticDiversity = false,
            )
        }.getOrNull() ?: return buildRequestSpecificStrategyFallback(
            goal = goal,
            task = task,
            role = role,
            minimumQueries = queryCount,
        ) to repaired.copy(summary = combinedSummary)
        if (!adaptiveResearchStrategyAnchorsRequest(strategy, goal.userRequest, goal.objectiveContract?.strongAnchors.orEmpty())) {
            return buildRequestSpecificStrategyFallback(
                goal = goal,
                task = task,
                role = role,
                minimumQueries = queryCount,
            ) to repaired.copy(summary = combinedSummary)
        }
        return strategy to repaired.copy(summary = combinedSummary)
    }

    private suspend fun refineFailedSearchQuery(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        task: AgentTask,
        failedQuery: AdaptiveResearchQuery,
        error: String,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): AdaptiveResearchQuery {
        val validation = SearchQueryValidator.validate(failedQuery.query, goal.userRequest, goal.resolvedResearchRequest)
        val validatedFailedQuery = if (validation is SearchQueryValidator.ValidationResult.Valid) validation.executionText else failedQuery.query
        val prompt = buildString {
            appendLine("The following web-search query failed to return results or reached an intelligence wall: '$validatedFailedQuery'")
            appendLine("Act as a high-intelligence research assistant. Instead of just refining the keywords, generate exactly one LATERAL research angle.")
            appendLine("Think like a human: if the direct entity search failed, pivot to synonyms, parent organizations, related technical standards, or alternative documentation sources (e.g. GitHub, Reddit, StackOverflow, or Government archives).")
            appendLine("Explain which lateral pivot you are using and what answer-changing evidence it targets.")
            appendLine()
            appendLine("Original request: ${goal.userRequest.take(500)}")
            appendLine("Current milestone: ${task.title}")
            appendLine("Information need: ${failedQuery.purpose}")
            appendLine("Failure reason: $error")
            appendLine()
            appendLine("Return only the requested structured metadata.")
        }

        fun payload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = AgentRoutingPolicy.guardModel(goal, if (goal.freeOnly || isFreeOnlyModel(modelId)) ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID else ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID),
                systemPrompt = "You are a persistent research assistant with advanced lateral thinking skills. When a search branch fails, you pivot to adjacent entities or different source types to find a way around the obstacle.",
                userPrompt = prompt,
                reasoningEffort = if (goal.freeOnly || isFreeOnlyModel(modelId)) "high" else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "search_query_refinement",
                freeOnly = goal.freeOnly
            )
            p.apply {
                put("temperature", 0.22)
                responseFormat?.let { put("response_format", it) }
            }
            return p to attr
        }

        val response = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = payload(jsonSchemaResponseFormat("search_query_refinement_v1", searchRefinementSchema())),
            jsonMode = payload(JSONObject().put("type", "json_object")),
            plain = payload(null),
            generation = generation,
            requestContext = requestContext,
        )
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Search query refinement")
        val refinementValidation = SearchQueryValidator.validate(root.optString("query"), goal.userRequest, goal.resolvedResearchRequest)
        val refinedQuery = if (refinementValidation is SearchQueryValidator.ValidationResult.Valid) {
            refinementValidation.executionText
        } else {
            throw ToolValidationException("Model produced an invalid search refinement: ${refinementValidation.let { if (it is SearchQueryValidator.ValidationResult.Rejected) it.reason else "Unknown" }}")
        }
        return AdaptiveResearchQuery(
            query = refinedQuery,
            purpose = failedQuery.purpose,
            expectedEvidence = failedQuery.expectedEvidence,
            dependsOnDiscovery = failedQuery.dependsOnDiscovery
        )
    }

    private fun isExtremelyGenericQuery(query: String, goal: AgentGoal): Boolean {
        val validation = SearchQueryValidator.validate(query, goal.userRequest, goal.resolvedResearchRequest)
        if (validation is SearchQueryValidator.ValidationResult.Rejected) return true
        val normalized = query.lowercase(Locale.US)
        return normalized.length < 10 || normalized.split(' ').size < 2
    }

    private fun isHighRelevanceSearchOutcome(query: String, result: ToolExecutionResult): Boolean {
        val json = result.outputJson
        val sources = parseToolSourceCitations(json)
        if (sources.isEmpty()) return false
        
        // If we have a lot of results, some noise is expected, but at least
        // some should match our core intent.
        val anchors = requestAnchorTokens(query)
            
        if (anchors.isEmpty()) return true
        
        val matchedSources = sources.count { source ->
            val text = "${source.title} ${source.excerpt} ${source.url}".lowercase(Locale.US)
            val matches = anchors.count { anchor -> text.contains(anchor) }
            matches >= minOf(2, anchors.size)
        }
        
        // High relevance if at least 25% of results match multiple anchors,
        // or at least 1 result match if we have few results.
        return matchedSources >= (sources.size * 0.25).coerceAtLeast(1.0)
    }

    private fun isHighRelevancePage(query: String, result: ToolExecutionResult?): Boolean {
        if (result == null) return false
        return isHighRelevanceSearchOutcome(query, result)
    }

    private fun applyAutomaticDisambiguation(query: String, goal: AgentGoal): String {
        val normalizedQuery = query.lowercase(Locale.US)
        val context = "${goal.userRequest} ${goal.title} ${goal.objective}".lowercase(Locale.US)
        
        val contract = goal.objectiveContract ?: return query
        val requiredContext = contract.strongAnchors
        
        if (requiredContext.isEmpty()) return query
        
        val contextMissing = requiredContext.none { it.lowercase(Locale.US) in normalizedQuery }
        
        if (contextMissing) {
            val disambiguator = requiredContext.first()
            return "$query $disambiguator"
        }
        
        return query
    }

    private suspend fun reconstructFailedInformationNeed(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        task: AgentTask,
        failedQuery: AdaptiveResearchQuery,
        error: String,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): AdaptiveResearchQuery {
        val validation = SearchQueryValidator.validate(failedQuery.query, goal.userRequest, goal.resolvedResearchRequest)
        val validatedFailedQuery = if (validation is SearchQueryValidator.ValidationResult.Valid) validation.executionText else failedQuery.query
        val prompt = buildString {
            appendLine("The direct search for this information has failed after multiple refinements: '$validatedFailedQuery'")
            appendLine("Act as a forensic researcher. When a direct fact is unsearchable or blocked, a human expert looks for INDIRECT evidence.")
            appendLine("Example: if a product's price is missing, look for sibling products, leaked forum discussions, or MSRP standards for the category.")
            appendLine("Example: if a person's statement is deleted, look for news mirrors, social media reactions, or Archive.org citations.")
            appendLine("What are 3 indirect search angles that could infer this answer? Select the STRONGEST one and return it as a new search query.")
            appendLine()
            appendLine("Original request: ${goal.userRequest.take(500)}")
            appendLine("Current milestone: ${task.title}")
            appendLine("Information need: ${failedQuery.purpose}")
            appendLine("Failure reason: $error")
            appendLine()
            appendLine("Return only the requested structured metadata.")
        }

        fun payload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = AgentRoutingPolicy.guardModel(goal, if (goal.freeOnly || isFreeOnlyModel(modelId)) ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID else ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID),
                systemPrompt = "You are a forensic investigator specializing in indirect evidence reconstruction. You solve unsearchable problems by finding surrounding clues and inferred data points.",
                userPrompt = prompt,
                reasoningEffort = "high",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "forensic_reconstruction",
                freeOnly = goal.freeOnly
            )
            p.apply {
                put("temperature", 0.3)
                responseFormat?.let { put("response_format", it) }
            }
            return p to attr
        }

        val response = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = payload(jsonSchemaResponseFormat("forensic_reconstruction_v1", searchRefinementSchema())),
            jsonMode = payload(JSONObject().put("type", "json_object")),
            plain = payload(null),
            generation = generation,
            requestContext = requestContext,
        )
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Forensic reconstruction")
        val refinementValidation = SearchQueryValidator.validate(root.optString("query"), goal.userRequest)
        val refinedQuery = if (refinementValidation is SearchQueryValidator.ValidationResult.Valid) {
            refinementValidation.executionText
        } else {
            throw ToolValidationException("Model produced an invalid forensic reconstruction: ${refinementValidation.let { if (it is SearchQueryValidator.ValidationResult.Rejected) it.reason else "Unknown" }}")
        }
        return AdaptiveResearchQuery(
            query = refinedQuery,
            purpose = "Forensic reconstruction: ${failedQuery.purpose}",
            expectedEvidence = "Indirect or inferred evidence supporting: ${failedQuery.expectedEvidence}",
            dependsOnDiscovery = "Direct search path exhaustion"
        )
    }

    private suspend fun createEvidenceDrivenFollowUpQuery(
        apiKey: String,
        modelId: String,
        goal: AgentGoal,
        task: AgentTask,
        strategy: AdaptiveResearchStrategy,
        priorQueries: List<AdaptiveResearchQuery>,
        sources: List<AgentSourceCitation>,
        fetchedPages: List<Pair<AgentSourceCitation, String>>,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): Pair<AdaptiveResearchQuery, AgentApiSummary> {
        val leadContext = buildString {
            appendLine("New source leads discovered in this pass:")
            sources.take(MAX_RABBIT_HOLE_SOURCE_LEADS).forEach { source ->
                appendLine("- ${source.title}")
                appendLine("  URL: ${source.url}")
                source.excerpt?.takeIf(String::isNotBlank)?.let { excerpt ->
                    appendLine("  Discovery snippet: ${excerpt.take(MAX_SOURCE_EXCERPT_CHARS)}")
                }
            }
            if (fetchedPages.isNotEmpty()) {
                appendLine()
                appendLine("Full-page material already read in this pass:")
                fetchedPages.take(MAX_RABBIT_HOLE_FULL_PAGES).forEach { (source, text) ->
                    appendLine("SOURCE: ${source.title}")
                    appendLine("URL: ${source.url}")
                    appendLine(text.take(MAX_RABBIT_HOLE_PAGE_CHARS))
                }
            }
        }.take(MAX_RABBIT_HOLE_CONTEXT_CHARS)
        val prompt = buildString {
            appendLine("Derive exactly one new web-search branch from a concrete entity, term, citation, dataset, discrepancy, method, boundary issue, or unanswered implication in the newly discovered evidence below.")
            appendLine("This must be a real evidence-driven follow-up that could not have been chosen responsibly before seeing these leads. Do not restate the original request, paraphrase an earlier query, use a generic modifier, or answer the question.")
            appendLine("Explain which discovery triggered the branch in depends_on_discovery and what answer-changing evidence the query is expected to find.")
            appendLine()
            appendLine("Original request:")
            appendLine(boundedText(goal.userRequest, MAX_EXECUTOR_REQUEST_CHARS))
            appendLine()
            appendLine("Current milestone:")
            appendLine(task.title)
            appendLine(task.instructions)
            appendLine()
            appendLine("Investigation follow-up rule:")
            appendLine(strategy.followUpRule)
            appendLine()
            appendLine("Queries already executed or queued in this pass:")
            priorQueries.forEach { appendLine("- ${it.query}") }
            appendLine()
            appendLine(leadContext)
        }

        fun payload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = modelId,
                systemPrompt = EVIDENCE_DRIVEN_FOLLOW_UP_SYSTEM_PROMPT,
                userPrompt = prompt,
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "evidence_driven_follow_up",
                freeOnly = goal.freeOnly
            )
            p.apply {
                put("temperature", 0.12)
                responseFormat?.let { put("response_format", it) }
            }
            return p to attr
        }

        val response = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = payload(jsonSchemaResponseFormat("evidence_driven_follow_up_v1", evidenceDrivenFollowUpSchema())),
            jsonMode = payload(JSONObject().put("type", "json_object")),
            plain = payload(null),
            generation = generation,
            requestContext = requestContext,
        )
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Evidence-driven follow-up query")
        fun requiredText(name: String, limit: Int): String = root.optString(name)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(limit)
            .also { require(it.isNotBlank()) { "Evidence-driven follow-up omitted $name." } }
        val query = requiredText("query", 500)
        val validation = SearchQueryValidator.validate(query, goal.userRequest)
        val queryText = if (validation is SearchQueryValidator.ValidationResult.Valid) {
            validation.executionText
        } else {
            throw IllegalArgumentException("Model produced an invalid evidence-driven follow-up: ${validation.let { if (it is SearchQueryValidator.ValidationResult.Rejected) it.reason else "Unknown" }}")
        }
        val followUp = AdaptiveResearchQuery(
            query = queryText,
            purpose = requiredText("purpose", 1_000),
            expectedEvidence = requiredText("expected_evidence", 1_000),
            dependsOnDiscovery = requiredText("depends_on_discovery", 1_000),
        )
        require(priorQueries.none { it.query.equals(followUp.query, ignoreCase = true) }) {
            "Evidence-driven follow-up repeated an earlier query."
        }
        return followUp to response.summary
    }

    private suspend fun executeResearchStrategyRefinement(
        apiKey: String,
        modelId: String,
        originalPrompt: String,
        rejectedStrategy: String,
        queryCount: Int,
        freeOnly: Boolean = false,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): RawAgentResponse {
        val prompt = buildString {
            appendLine("The previous investigation strategy was malformed, incomplete, repetitive, or generic. Produce a corrected request-specific strategy.")
            appendLine("Do not answer the research question and do not introduce findings. Re-reason from the supplied request and evidence context. Queries must target at least $queryCount different information needs, with different purposes and expected evidence, rather than adding interchangeable search modifiers.")
            appendLine()
            appendLine("Original strategy request:")
            appendLine(originalPrompt.take(MAX_ADAPTIVE_STRATEGY_PROMPT_CHARS))
            appendLine()
            appendLine("Rejected strategy:")
            appendLine(rejectedStrategy.take(MAX_STRUCTURE_REPAIR_CHARS))
        }
        fun payload(responseFormat: JSONObject?): Pair<JSONObject, ProviderResponseAttribution> {
            val (p, attr) = basePayload(
                modelId = when {
                    freeOnly -> ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID
                    ProviderRecoveryPolicy.isAutoRouter(modelId) -> modelId
                    else -> ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID
                },
                systemPrompt = RESEARCH_STRATEGY_REFINEMENT_SYSTEM_PROMPT,
                userPrompt = prompt,
                reasoningEffort = if (freeOnly || isFreeOnlyModel(modelId)) "high" else "medium",
                role = AgentTaskRole.PRIMARY_REASONING,
                selectionReason = "research_strategy_refinement",
                freeOnly = freeOnly
            )
            p.apply {
                put("temperature", 0.05)
                responseFormat?.let { put("response_format", it) }
            }
            return p to attr
        }
        return executeStructuredWithFallback(
            apiKey = apiKey,
            strict = payload(jsonSchemaResponseFormat("adaptive_research_strategy_refinement_v1", researchStrategySchema(queryCount))),
            jsonMode = payload(JSONObject().put("type", "json_object")),
            plain = payload(null),
            generation = generation,
            requestContext = requestContext,
        )
    }


    private suspend fun executeStructuredWithToolsFallback(
        apiKey: String,
        strict: Pair<JSONObject, ProviderResponseAttribution>,
        jsonMode: Pair<JSONObject, ProviderResponseAttribution>,
        plain: Pair<JSONObject, ProviderResponseAttribution>,
        generation: Int = 0,
        onProgress: (AgentSourceCitation) -> Unit = {},
        requestContext: ProviderRequestContext.Mission,
        goal: AgentGoal? = null,
        maxAttempts: Int = 3,
    ): RawAgentResponse = runCatching { 
        executeToolCompatibilityLadder(apiKey, strict.first, strict.second, generation, onProgress, requestContext, goal, maxAttempts, wireVariantKind = ProviderWireVariantKind.STRICT_SCHEMA) 
    }.recoverCatching { strictError ->
        if (!strictError.isStructuredOutputUnsupported()) throw strictError
        executeToolCompatibilityLadder(apiKey, jsonMode.first, jsonMode.second, generation, onProgress, requestContext, goal, maxAttempts, wireVariantKind = ProviderWireVariantKind.JSON_OBJECT)
    }.recoverCatching { jsonModeError ->
        if (!jsonModeError.isStructuredOutputUnsupported()) throw jsonModeError
        executeToolCompatibilityLadder(apiKey, plain.first, plain.second, generation, onProgress, requestContext, goal, maxAttempts, wireVariantKind = ProviderWireVariantKind.PLAIN_JSON)
    }.getOrThrow()

    /**
     * OpenRouter server tools are an optional capability layer because model
     * and provider routes can differ in support. Research must not collapse
     * from a full council straight to an unsourced answer, so this ladder
     * removes only incompatible advanced tools while preserving search,
     * fetch, datetime, and app-defined function tools for as long as possible.
     */
    private suspend fun executeToolCompatibilityLadder(
        apiKey: String,
        originalPayload: JSONObject,
        attribution: ProviderResponseAttribution,
        generation: Int = 0,
        onProgress: (AgentSourceCitation) -> Unit = {},
        requestContext: ProviderRequestContext.Mission,
        goal: AgentGoal? = null,
        maxAttempts: Int = 3,
        wireVariantKind: ProviderWireVariantKind
    ): RawAgentResponse {
        val toolExecutionCache = ConcurrentHashMap<String, String>()
        val candidates = originalPayload.researchToolCompatibilityCandidates()
        var lastError: Throwable? = null
        candidates.forEachIndexed { index, candidate ->
            try {
                val response = executeToolAwareWithChoiceFallback(
                    apiKey = apiKey,
                    payload = candidate.payload,
                    attribution = attribution,
                    generation = generation,
                    onProgress = onProgress,
                    requestContext = requestContext,
                    goal = goal,
                    maxAttempts = maxAttempts,
                    toolExecutionCache = toolExecutionCache,
                    wireVariantKind = wireVariantKind,
                    wireVariantOrdinal = index
                )
                if (index == 0) return response
                return response.copy(
                    toolExecutions = listOf(
                        AgentToolExecution(
                            toolName = "research_compatibility",
                            summary = "OpenRouter compatibility mode: ${candidate.label}.",
                            succeeded = true,
                        ),
                    ) + response.toolExecutions,
                )
            } catch (error: Throwable) {
                lastError = error
                val hasAnotherCandidate = index < candidates.lastIndex
                if (!hasAnotherCandidate || !error.isServerToolCompatibilityError()) throw error
            }
        }
        throw lastError ?: OpenRouterException(null, "No compatible research-tool configuration was available.")
    }

    /**
     * A specific function choice is the most portable way to make a skipped
     * tool milestone advance, but some routed providers reject that optional
     * parameter. Retry the same focused schema once with automatic choice; the
     * deterministic tool gate and bounded attempt window still prevent prose
     * from being mistaken for a successful tool result.
     */
    private suspend fun executeToolAwareWithChoiceFallback(
        apiKey: String,
        payload: JSONObject,
        attribution: ProviderResponseAttribution,
        generation: Int = 0,
        onProgress: (AgentSourceCitation) -> Unit = {},
        requestContext: ProviderRequestContext.Mission,
        goal: AgentGoal? = null,
        maxAttempts: Int = 3,
        toolExecutionCache: ConcurrentHashMap<String, String>,
        wireVariantKind: ProviderWireVariantKind = ProviderWireVariantKind.PRIMARY,
        wireVariantOrdinal: Int = 0
    ): RawAgentResponse = runCatching {
        executeToolAwareJsonRequest(apiKey, payload, attribution, generation, onProgress, requestContext, goal, maxAttempts, toolExecutionCache, wireVariantKind, wireVariantOrdinal)
    }.recoverCatching { error ->
        if (!payload.has("tool_choice") || !error.isToolChoiceCompatibilityError()) throw error
        executeToolAwareJsonRequest(
            apiKey,
            JSONObject(payload.toString()).also(::relaxRequiredFunctionToolChoice),
            attribution,
            generation,
            onProgress,
            requestContext,
            goal,
            maxAttempts,
            toolExecutionCache,
            wireVariantKind,
            wireVariantOrdinal + 100 // Use distinct range for tool choice fallback
        )
    }.getOrThrow()

    private data class ResearchToolCandidate(
        val label: String,
        val payload: JSONObject,
    )

    private fun JSONObject.researchToolCompatibilityCandidates(): List<ResearchToolCandidate> {
        if (!hasServerResearchTools()) return listOf(ResearchToolCandidate("app-defined tools", this))
        return buildList {
            add(
                ResearchToolCandidate(
                    "full research council",
                    cloneResearchRequestPayload(this@researchToolCompatibilityCandidates),
                ),
            )
            add(ResearchToolCandidate("core web search, fetch, and datetime", withAllowedServerTools(CORE_SERVER_TOOL_TYPES)))
            add(ResearchToolCandidate("repeated web search", withAllowedServerTools(SEARCH_ONLY_SERVER_TOOL_TYPES)))
            add(ResearchToolCandidate("legacy web-search plugin", withLegacyWebPlugin()))
        }.distinctBy { it.payload.toString() }
    }

    private fun JSONObject.hasServerResearchTools(): Boolean {
        val tools = optJSONArray("tools") ?: return false
        for (index in 0 until tools.length()) {
            val type = tools.optJSONObject(index)?.optString("type").orEmpty()
            if (type.startsWith("openrouter:")) return true
        }
        return false
    }

    private fun JSONObject.withAllowedServerTools(allowedTypes: Set<String>): JSONObject {
        val copy = JSONObject(toString())
        val existing = copy.optJSONArray("tools") ?: return copy
        val retained = JSONArray()
        for (index in 0 until existing.length()) {
            val tool = existing.optJSONObject(index) ?: continue
            val type = tool.optString("type")
            if (type == "function" || type in allowedTypes) retained.put(tool)
        }
        if (retained.length() == 0) copy.remove("tools") else copy.put("tools", retained)
        copy.remove("plugins")
        return copy
    }

    private fun JSONObject.withLegacyWebPlugin(): JSONObject {
        val copy = withAllowedServerTools(emptySet())
        val plugins = JSONArray()
        val existingPlugins = copy.optJSONArray("plugins")
        if (existingPlugins != null) {
            for (index in 0 until existingPlugins.length()) {
                existingPlugins.optJSONObject(index)?.let(plugins::put)
            }
        }
        plugins.put(
            JSONObject()
                .put("id", "web")
                .put("max_results", LEGACY_WEB_PLUGIN_RESULTS),
        )
        copy.put("plugins", plugins)
        return copy
    }

    internal suspend fun executeToolAwareJsonRequest(
        apiKey: String,
        originalPayload: JSONObject,
        attribution: ProviderResponseAttribution,
        generation: Int = 0,
        onProgress: (AgentSourceCitation) -> Unit = {},
        requestContext: ProviderRequestContext.Mission,
        goal: AgentGoal? = null,
        maxAttempts: Int = 3,
        priorOutputsBySignature: ConcurrentHashMap<String, String>,
        wireVariantKind: ProviderWireVariantKind = ProviderWireVariantKind.PRIMARY,
        wireVariantOrdinal: Int = 0
    ): RawAgentResponse {
        val runtime = toolRuntime ?: return executeJsonRequest(apiKey, originalPayload, attribution, generation, requestContext, maxAttempts, wireVariantKind, wireVariantOrdinal)
        val guardedModelId = goal?.let { AgentRoutingPolicy.guardModel(it, originalPayload.optString("model")) }
            ?: originalPayload.optString("model")
        
        var payload = JSONObject(originalPayload.toString()).put("model", guardedModelId)
        val role = attribution.role
        val selectionReason = attribution.selectionReason
        
        val messages = payload.getJSONArray("messages")
        val accumulatedSources = linkedMapOf<String, AgentSourceCitation>()
        val verifiedUrls = mutableSetOf<String>()
        val unavailableSourceKeys = mutableSetOf<String>()
        val executions = mutableListOf<AgentToolExecution>()
        val usedProviderCallIds = mutableSetOf<String>()
        var responseId: String? = null
        var resolvedModel: String? = null
        var finishReason: String? = null
        var nativeFinishReason: String? = null
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var totalCost = 0.0
        var webSearchRequests = 0
        var webFetchRequests = 0
        var discoveredLeads = 0

        var previousToolCallSignatures: List<String>? = null
        var repeatedNoProgressCycles = 0
        var round = 0
        var totalAcceptedCalls = 0
        var toolTranscriptCharacters = 0
        var finalToolFreeAttempted = false

        fun sourceKey(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            return runCatching {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
                val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
                if (scheme.isBlank() || host.isBlank()) return@runCatching trimmed.removeSuffix("/")
                val port = if (uri.port == -1) "" else ":${uri.port}"
                val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
                val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                "$scheme://$host$port$path$query"
            }.getOrDefault(trimmed.removeSuffix("/"))
        }

        val allowedSourceKeys = goal?.evidence?.flatMap { it.sources }?.map { sourceKey(it.url) }?.toSet() ?: emptySet()

        fun preserveSource(
            source: AgentSourceCitation,
            successfulFetch: Boolean = false,
            fromTool: Boolean = false
        ) {
            val key = sourceKey(source.url)
            if (successfulFetch) verifiedUrls.add(source.url)

            if (!fromTool && goal != null) {
                val isVerifiedInLoop = verifiedUrls.contains(source.url) || verifiedUrls.any { sourceKey(it) == key }
                if (!isVerifiedInLoop && !allowedSourceKeys.contains(key)) {
                    // Prune fabricated citation from model text/annotations
                    return
                }
            }

            val matchingEntries = accumulatedSources.entries
                .filter { entry -> sourceKey(entry.key) == key }
            val richestExistingExcerpt = matchingEntries
                .maxOfOrNull { entry -> entry.value.excerpt.orEmpty().length }
                ?: -1
            if (successfulFetch) {
                unavailableSourceKeys.remove(key)
                matchingEntries.forEach { entry -> accumulatedSources.remove(entry.key) }
                accumulatedSources[source.url] = source
                onProgress(source)
            } else if (
                key !in unavailableSourceKeys &&
                (matchingEntries.isEmpty() || source.excerpt.orEmpty().length > richestExistingExcerpt)
            ) {
                matchingEntries.forEach { entry -> accumulatedSources.remove(entry.key) }
                accumulatedSources[source.url] = source
                onProgress(source)
            }
        }

        fun rejectUnavailableSource(url: String) {
            val key = sourceKey(url)
            unavailableSourceKeys += key
            accumulatedSources.keys
                .filter { sourceKey(it) == key }
                .forEach(accumulatedSources::remove)
        }

        fun permanentFailedFetchUrl(call: OpenRouterToolCall, error: Throwable): String? {
            if (call.name != "public_web_fetch") return null
            val isPermanent = error is com.david.openassistant.domain.tools.PdfUnsupportedException ||
                PERMANENT_PUBLIC_FETCH_FAILURE_PATTERN.containsMatchIn(error.message.orEmpty())
            if (!isPermanent) return null
            return runCatching { JSONObject(call.argumentsJson).optString("url").trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        }

        try {
            while (true) {
                if (
                    !finalToolFreeAttempted &&
                    localToolBudgetExhausted(round, totalAcceptedCalls, toolTranscriptCharacters)
                ) {
                    payload = finalToolFreeCompletionPayload(payload)
                    finalToolFreeAttempted = true
                }

                val root = executeRawJsonRequest(apiKey, payload, attribution, generation, requestContext, wireVariantKind = wireVariantKind, wireVariantOrdinal = wireVariantOrdinal + round)
                responseId = root.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: responseId
                resolvedModel = root.optString("model").takeIf { it.isNotBlank() && it != "null" } ?: resolvedModel

                val usage = root.optJSONObject("usage")
                promptTokens += usage.optIntOrNull("prompt_tokens") ?: usage.optIntOrNull("input_tokens") ?: 0
                completionTokens += usage.optIntOrNull("completion_tokens") ?: usage.optIntOrNull("output_tokens") ?: 0
                totalTokens += usage.optIntOrNull("total_tokens") ?: 0
                totalCost += usage.optDoubleOrNull("cost") ?: 0.0
                webSearchRequests += providerWebSearchRequestCount(usage)
                root.optJSONObject("error")?.let { error ->
                    throw OpenRouterException(
                        error.optInt("code").takeIf { it > 0 },
                        SecretRedactor.redact(error.providerErrorMessage(), apiKey),
                    )
                }

                val choice = root.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw OpenRouterException(null, "The agent model returned no response choice.")
                finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" } ?: finishReason
                nativeFinishReason = choice.optJSONObject("openrouter_metadata")?.optString("native_finish_reason")
                    ?: root.optJSONObject("openrouter_metadata")?.optString("native_finish_reason")
                    ?: nativeFinishReason

                val message = choice.optJSONObject("message")
                    ?: throw OpenRouterException(null, "The agent model returned an invalid response message.")
                val providerSources = parseSourceCitations(message.optJSONArray("annotations"))
                providerSources.forEach { source ->
                    preserveSource(source, fromTool = false)
                }
                executions += providerResearchToolExecutions(root, providerSources)
                val calls = message.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0) {
                    if (finalToolFreeAttempted) {
                        buildToolCheckpointResponse(
                            reason = "The provider requested more tools after the runtime's final tool-free completion request.",
                            responseId = responseId,
                            resolvedModel = resolvedModel,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens,
                            totalCost = totalCost,
                            webSearchRequests = webSearchRequests,
                            accumulatedSources = accumulatedSources,
                            executions = executions,
                        )?.let { return it }
                        throw OpenRouterException(
                            null,
                            "The model did not return a structured milestone after the bounded tool window. Completed tool work was preserved for checkpoint recovery.",
                        )
                    }

                    val remainingTranscriptCharacters =
                        (MAX_LOCAL_TOOL_TRANSCRIPT_CHARS - toolTranscriptCharacters).coerceAtLeast(0)
                    val acceptedRecordLimit = minOf(
                        MAX_PROVIDER_TOOL_CALL_RECORDS_PER_ROUND,
                        (remainingTranscriptCharacters / MIN_RESERVED_TOOL_RESULT_CHARS).coerceAtLeast(1),
                    )
                    val acceptedCallJson = JSONArray()
                    val acceptedCalls = mutableListOf<Pair<OpenRouterToolCall, OpenRouterToolCall>>()
                    for (index in 0 until calls.length()) {
                        if (acceptedCalls.size >= acceptedRecordLimit) break
                        val rawCall = calls.optJSONObject(index) ?: continue
                        val function = rawCall.optJSONObject("function") ?: continue
                        val providerCall = OpenRouterToolCall(
                            id = normalizedProviderToolCallId(
                                rawId = rawCall.optString("id"),
                                fallbackId = "tool_call_${round}_$index",
                                usedIds = usedProviderCallIds,
                            ),
                            name = function.optString("name"),
                            argumentsJson = function.optString("arguments").ifBlank { "{}" },
                        )
                        val call = canonicalizeProviderToolCall(providerCall)
                        acceptedCallJson.put(
                            JSONObject(rawCall.toString())
                                .put("id", providerCall.id),
                        )
                        acceptedCalls += providerCall to call
                    }
                    if (acceptedCalls.isEmpty()) {
                        buildToolCheckpointResponse(
                            reason = "The provider returned tool-call records without usable function metadata.",
                            responseId = responseId,
                            resolvedModel = resolvedModel,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens,
                            totalCost = totalCost,
                            webSearchRequests = webSearchRequests,
                            accumulatedSources = accumulatedSources,
                            executions = executions,
                        )?.let { return it }
                        throw OpenRouterException(null, "The provider returned malformed tool-call records.")
                    }
                    val omittedCallRecords = calls.length() - acceptedCalls.size
                    if (omittedCallRecords > 0) {
                        executions += AgentToolExecution(
                            toolName = "tool_call_budget",
                            summary = "Ignored $omittedCallRecords excess or malformed provider tool-call record(s) beyond the bounded acceptance window.",
                            succeeded = false,
                        )
                    }

                    val currentToolCallSignatures = acceptedCalls.map { (_, call) ->
                        normalizedToolCallSignature(call)
                    }
                    repeatedNoProgressCycles = if (currentToolCallSignatures == previousToolCallSignatures) {
                        repeatedNoProgressCycles + 1
                    } else {
                        0
                    }
                    previousToolCallSignatures = currentToolCallSignatures
                    if (repeatedNoProgressCycles >= 2) {
                        buildToolCheckpointResponse(
                            reason = "The provider repeated identical tool requests; completed tool work was preserved before changing execution strategy.",
                            responseId = responseId,
                            resolvedModel = resolvedModel,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens,
                            totalCost = totalCost,
                            webSearchRequests = webSearchRequests,
                            accumulatedSources = accumulatedSources,
                            executions = executions,
                        )?.let { return it }
                        throw OpenRouterException(
                            null,
                            "The model repeated the same tool requests without making progress. The milestone will restart from its durable checkpoint.",
                        )
                    }

                    messages.put(
                        JSONObject(message.toString())
                            .put("role", "assistant")
                            .put("tool_calls", acceptedCallJson),
                    )
                    val executableCallsLimit = allowedLocalToolCalls(
                        requestedCalls = acceptedCalls.size,
                        totalAcceptedCalls = totalAcceptedCalls,
                    )
                    val roundExecutionLock = Mutex()
                    val roundExecutionJobs = ConcurrentHashMap<String, Deferred<String>>()
                    
                    coroutineScope {
                        acceptedCalls.mapIndexed { index, (providerCall, call) ->
                            async {
                                val signature = normalizedToolCallSignature(call)
                                val rawOutputJson = if (index >= executableCallsLimit) {
                                    "{\"status\":\"skipped\",\"reason\":\"bounded local-tool budget reached\"}"
                                } else {
                                    val priorOutput = priorOutputsBySignature[signature]
                                    if (priorOutput != null) {
                                        roundExecutionLock.withLock {
                                            totalAcceptedCalls += 1
                                            executions += AgentToolExecution(
                                                toolName = "cached_${call.name}",
                                                summary = "Reused the prior '${call.name}' result for an identical tool request.",
                                                succeeded = true,
                                            )
                                        }
                                        priorOutput
                                    } else {
                                        // Handle intra-round parallel deduplication
                                        roundExecutionJobs.computeIfAbsent(signature) {
                                            async {
                                                try {
                                                    diagnostics?.info(
                                                        event = "tool_call_started",
                                                        component = "tool",
                                                        fields = mapOf(
                                                            "goal_id" to goal?.id,
                                                            "task_id" to requestContext.taskId,
                                                            "tool_call_id" to call.id,
                                                            "tool_name" to call.name
                                                        )
                                                    )
                                                    if (researchMonitor?.status()?.detailedContentCaptureEnabled == true) {
                                                        diagnostics?.contentPreview(
                                                            kind = "tool_input",
                                                            content = call.argumentsJson,
                                                            goalId = goal?.id,
                                                            taskId = requestContext.taskId,
                                                            exchangeId = call.id,
                                                            extraFields = mapOf("tool_name" to call.name)
                                                        )
                                                    }

                                                    val result = runtime.execute(
                                                        call = call,
                                                        apiKey = apiKey,
                                                        modelId = resolvedModel ?: payload.optString("model"),
                                                        goal = goal,
                                                    )
                                                    
                                                    diagnostics?.info(
                                                        event = "tool_call_completed",
                                                        component = "tool",
                                                        fields = mapOf(
                                                            "goal_id" to goal?.id,
                                                            "task_id" to requestContext.taskId,
                                                            "tool_call_id" to call.id,
                                                            "tool_name" to call.name,
                                                            "duration_ms" to result.durationMs,
                                                            "result_size" to result.outputJson.length
                                                        )
                                                    )
                                                    if (researchMonitor?.status()?.detailedContentCaptureEnabled == true) {
                                                        diagnostics?.contentPreview(
                                                            kind = "tool_result",
                                                            content = result.outputJson,
                                                            goalId = goal?.id,
                                                            taskId = requestContext.taskId,
                                                            exchangeId = call.id,
                                                            extraFields = mapOf("tool_name" to call.name)
                                                        )
                                                    }
                                                    
                                                    val toolCitations = parseToolSourceCitations(result.outputJson)
                                                    roundExecutionLock.withLock {
                                                        totalAcceptedCalls += 1
                                                        promptTokens += result.promptTokens
                                                        completionTokens += result.completionTokens
                                                        totalTokens += result.totalTokens
                                                        totalCost += result.costUsd
                                                        webSearchRequests += result.webSearchRequests
                                                        if (call.name == "public_web_fetch") webFetchRequests += 1
                                                        if (call.name == "public_web_fetch") {
                                                            val fetchPayload = runCatching { JSONObject(result.outputJson) }.getOrNull()
                                                            discoveredLeads += fetchPayload?.optJSONArray("discovered_leads")?.length() ?: 0
                                                        }
                                                        executions += AgentToolExecution(
                                                            toolName = call.name,
                                                            summary = buildString {
                                                                if (providerCall.name != call.name) {
                                                                    append("Recovered provider tool alias '${providerCall.name}' as '${call.name}'. ")
                                                                }
                                                                append(result.displaySummary)
                                                            }.take(600),
                                                            succeeded = true,
                                                        )
                                                        toolCitations.forEach { source ->
                                                            preserveSource(
                                                                source = source,
                                                                successfulFetch = call.name == "public_web_fetch",
                                                                fromTool = true,
                                                            )
                                                        }
                                                        priorOutputsBySignature[signature] = result.outputJson
                                                    }
                                                    result.outputJson
                                                } catch (error: Throwable) {
                                                    val messageText = error.toAgentFailureMessage("Local tool execution failed.").take(1_000)
                                                    roundExecutionLock.withLock {
                                                        totalAcceptedCalls += 1
                                                        executions += AgentToolExecution(
                                                            toolName = call.name,
                                                            summary = messageText,
                                                            succeeded = false,
                                                        )
                                                        permanentFailedFetchUrl(call, error)?.let { failedUrl ->
                                                            rejectUnavailableSource(failedUrl)
                                                            if (goal != null && error is com.david.openassistant.domain.tools.PdfUnsupportedException) {
                                                                store?.updateGoal(goal.id) { current ->
                                                                    if (current.blockedSources.any { it.canonicalUrl == failedUrl }) {
                                                                        current
                                                                    } else {
                                                                        val record = BlockedSourceRecord(
                                                                            canonicalDocumentId = null,
                                                                            canonicalUrl = failedUrl,
                                                                            routeKind = "PDF",
                                                                            failureClass = "PDF_UNSUPPORTED",
                                                                            sourceTaskId = requestContext.taskId
                                                                        )
                                                                        current.copy(blockedSources = current.blockedSources + record)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    JSONObject()
                                                        .put("status", "error")
                                                        .put("tool_name", providerCall.name)
                                                        .put("error", messageText)
                                                        .toString()
                                                }
                                            }
                                        }.await().also { 
                                            // Record "reused" execution for duplicate calls in the same round
                                            val firstIndex = acceptedCalls.indexOfFirst { normalizedToolCallSignature(it.second) == signature }
                                            if (index > firstIndex) {
                                                 roundExecutionLock.withLock {
                                                     executions += AgentToolExecution(
                                                         toolName = "reused_${call.name}",
                                                         summary = "Reused the concurrent result from an identical '${call.name}' request in this round.",
                                                         succeeded = true,
                                                     )
                                                 }
                                            }
                                        }
                                    }
                                }
                                
                                val remainingRecords = acceptedCalls.size - index - 1
                                val availableForResult = (
                                    MAX_LOCAL_TOOL_TRANSCRIPT_CHARS -
                                        toolTranscriptCharacters -
                                        (remainingRecords * MIN_RESERVED_TOOL_RESULT_CHARS)
                                    ).coerceAtLeast(0)
                                val outputJson = boundedLocalToolResult(
                                    rawResult = rawOutputJson,
                                    remainingTranscriptCharacters = availableForResult,
                                ).ifBlank { "{}" }
                                
                                roundExecutionLock.withLock {
                                    toolTranscriptCharacters += outputJson.length
                                    messages.put(
                                        JSONObject()
                                            .put("role", "tool")
                                            .put("tool_call_id", call.id)
                                            .put("name", providerCall.name)
                                            .put("content", outputJson),
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                    payload.put("messages", messages)
                    // A specific function choice applies only to the recovery's
                    // first round. Once real output is available, let the model
                    // synthesize or choose a different focused tool instead of
                    // forcing the same call until the round ceiling.
                    relaxRequiredFunctionToolChoice(payload)
                    payload.put("tools", refreshLocalFunctionTools(payload.optJSONArray("tools"), runtime))
                    round += 1
                    continue
                }

                val content = JsonEnvelopeParser.messageText(message)
                    ?: buildToolCheckpointResponse(
                        reason = "The provider returned no structured text after successful tool execution; the retrieved evidence was preserved for a checkpoint completion pass.",
                        responseId = responseId,
                        resolvedModel = resolvedModel,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        totalCost = totalCost,
                        webSearchRequests = webSearchRequests,
                        accumulatedSources = accumulatedSources,
                        executions = executions,
                    )?.let { return it }
                    ?: throw OpenRouterException(null, "The agent model returned no usable text after tool execution.")
                recoverHttpsSourceCitations(content).forEach { source ->
                    preserveSource(source, fromTool = false)
                }
                return RawAgentResponse(
                    content = content,
                    summary = AgentApiSummary(
                        responseId = responseId,
                        resolvedModel = resolvedModel,
                        role = role,
                        selectionReason = selectionReason,
                        finishReason = finishReason,
                        nativeFinishReason = nativeFinishReason,
                        promptTokens = promptTokens.takeIf { it > 0 },
                        completionTokens = completionTokens.takeIf { it > 0 },
                        totalTokens = totalTokens.takeIf { it > 0 },
                        costUsd = totalCost.takeIf { it > 0.0 },
                        webSearchRequests = webSearchRequests.takeIf { it > 0 },
                        webFetchRequests = webFetchRequests.takeIf { it > 0 },
                        discoveredLeads = discoveredLeads.takeIf { it > 0 },
                    ),
                    sources = accumulatedSources.values.take(MAX_SOURCE_CITATIONS).toList(),
                    toolExecutions = executions.toList(),
                    verifiedUrls = verifiedUrls
                )
            }
        } catch (error: Throwable) {
            if (error is TerminalPersistenceException || error is CancellationException) throw error
            buildToolCheckpointResponse(
                reason = error.toAgentFailureMessage(
                    "A provider call failed after successful tool execution.",
                ),
                responseId = responseId,
                resolvedModel = resolvedModel,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                totalCost = totalCost,
                webSearchRequests = webSearchRequests,
                accumulatedSources = accumulatedSources,
                executions = executions,
            )?.let { return it }
            throw error.withAgentUsage(
                AgentApiSummary(
                    responseId = responseId,
                    resolvedModel = resolvedModel,
                    role = role,
                    selectionReason = selectionReason,
                    promptTokens = promptTokens.takeIf { it > 0 },
                    completionTokens = completionTokens.takeIf { it > 0 },
                    totalTokens = totalTokens.takeIf { it > 0 },
                    costUsd = totalCost.takeIf { it > 0.0 },
                    webSearchRequests = webSearchRequests.takeIf { it > 0 },
                    webFetchRequests = webFetchRequests.takeIf { it > 0 },
                    discoveredLeads = discoveredLeads.takeIf { it > 0 },
                ),
            )
        }
    }

    private fun buildResearchBootstrapFailureCheckpoint(
        error: Throwable,
        bootstrap: ResearchBootstrap,
    ): RawAgentResponse? {
        if (error is TerminalPersistenceException || error is CancellationException) return null
        val hasRetrievedWebEvidence = bootstrap.sources.size >= 2 && bootstrap.executions.any {
            it.succeeded && it.toolName in RESEARCH_AUDIT_TOOL_NAMES
        }
        if (!hasRetrievedWebEvidence) return null
        val content = buildIncompleteToolCheckpointJson(
            reason = error.toAgentFailureMessage(
                "The provider continuation failed after the research bootstrap completed.",
            ),
            executions = bootstrap.executions,
            distinctSourceCount = bootstrap.sources.size,
        ) ?: return null
        // executeTask attaches the bootstrap's sources, audit, and usage once
        // after this response is returned, so this envelope carries content only.
        return RawAgentResponse(
            content = content,
            summary = AgentApiSummary(),
            sources = emptyList(),
            toolExecutions = emptyList(),
        )
    }

    private fun buildToolCheckpointResponse(
        reason: String,
        responseId: String?,
        resolvedModel: String?,
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
        totalCost: Double,
        webSearchRequests: Int,
        accumulatedSources: Map<String, AgentSourceCitation>,
        executions: List<AgentToolExecution>,
    ): RawAgentResponse? {
        val checkpoint = buildIncompleteToolCheckpointJson(
            reason = reason,
            executions = executions,
            distinctSourceCount = accumulatedSources.size,
        ) ?: return null
        return RawAgentResponse(
            content = checkpoint,
            summary = AgentApiSummary(
                responseId = responseId,
                resolvedModel = resolvedModel,
                promptTokens = promptTokens.takeIf { it > 0 },
                completionTokens = completionTokens.takeIf { it > 0 },
                totalTokens = totalTokens.takeIf { it > 0 },
                costUsd = totalCost.takeIf { it > 0.0 },
                webSearchRequests = webSearchRequests.takeIf { it > 0 },
            ),
            sources = accumulatedSources.values.take(MAX_SOURCE_CITATIONS).toList(),
            toolExecutions = executions.toList(),
        )
    }

    private fun refreshLocalFunctionTools(
        existing: JSONArray?,
        runtime: AutonomousToolRuntime,
    ): JSONArray {
        val refreshed = JSONArray()
        val allowedFunctionNames = linkedSetOf<String>()
        existing?.let { array ->
            for (index in 0 until array.length()) {
                val tool = array.optJSONObject(index) ?: continue
                if (tool.optString("type") == "function") {
                    tool.optJSONObject("function")
                        ?.optString("name")
                        ?.takeIf(String::isNotBlank)
                        ?.let(allowedFunctionNames::add)
                } else {
                    refreshed.put(tool)
                }
            }
        }
        val canCreateRecipes = "create_tool_recipe" in allowedFunctionNames
        runtime.definitions()
            .asSequence()
            .filter { definition ->
                definition.name in allowedFunctionNames ||
                    (canCreateRecipes && definition.name.startsWith("recipe_"))
            }
            .take(MAX_REFRESHED_LOCAL_TOOL_DEFINITIONS)
            .forEach { definition ->
                refreshed.put(definition.toOpenRouterFunctionTool())
            }
        return refreshed
    }

    private suspend fun executeBriefingStructuredWithFallback(
        apiKey: String,
        strict: Pair<JSONObject, ProviderResponseAttribution>,
        jsonMode: Pair<JSONObject, ProviderResponseAttribution>,
        plain: Pair<JSONObject, ProviderResponseAttribution>,
    ): RawAgentResponse = runCatching { executeBriefingJsonRequest(apiKey, strict.first, strict.second) }
        .recoverCatching { strictError ->
            if (!strictError.isStructuredOutputUnsupported()) throw strictError
            executeBriefingJsonRequest(apiKey, jsonMode.first, jsonMode.second)
        }
        .recoverCatching { jsonModeError ->
            if (!jsonModeError.isStructuredOutputUnsupported()) throw jsonModeError
            executeBriefingJsonRequest(apiKey, plain.first, plain.second)
        }
        .getOrThrow()

    private suspend fun executeBriefingJsonRequest(
        apiKey: String,
        payload: JSONObject,
        attribution: ProviderResponseAttribution,
    ): RawAgentResponse {
        val startedAt = System.currentTimeMillis()
        val context = ProviderRequestContext.Infrastructure(UUID.randomUUID().toString(), "research_briefing")
        val body = executeNonMissionCapturedOpenRouterBody(apiKey, payload, attribution, context)
        // Non-mission path doesn't track status code reliably through the same loop yet, assume 200 if it returned body
        return parseResponse(body, apiKey, attribution, 200, System.currentTimeMillis() - startedAt, "non-mission")
    }

    private suspend fun executeNonMissionCapturedOpenRouterBody(
        apiKey: String,
        canonicalPayload: JSONObject,
        attribution: ProviderResponseAttribution,
        requestContext: ProviderRequestContext,
    ): String {
        val preparedRequest = prepareOpenRouterWireRequest(
            canonicalPayload = canonicalPayload,
            requestContext = requestContext,
            responseAttribution = attribution
        )
        val tracker = TransportTracker()
        val request = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("X-OpenRouter-Metadata", "enabled")
            .header("User-Agent", "OpenAssistant-Android/${BuildConfig.VERSION_NAME}")
            .post(preparedRequest.wirePayloadText.toRequestBody(JSON_MEDIA_TYPE))
            .tag(TransportTracker::class.java, tracker)
            .tag(ProviderTransportContext::class.java, ProviderTransportContext("non-mission", "non-mission-exchange"))
            .build()
        val response = client.newCall(request).execute()
        val rawBody = response.body.string()
        if (!response.isSuccessful) throw response.toException(rawBody, apiKey)
        return rawBody
    }

    internal open suspend fun executeRawJsonRequest(
        apiKey: String,
        payload: JSONObject,
        attribution: ProviderResponseAttribution,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
        wireVariantKind: ProviderWireVariantKind = ProviderWireVariantKind.PRIMARY,
        wireVariantOrdinal: Int = 0
    ): JSONObject {
        val result = executeCapturedOpenRouterBody(apiKey, payload, attribution, "agent_tool_aware_chat", generation, requestContext, wireVariantKind = wireVariantKind, wireVariantOrdinal = wireVariantOrdinal)
        return when (result) {
            is MissionDispatchResult.Success -> JsonEnvelopeParser.requireObject(result.body, "OpenRouter tool response")
            is MissionDispatchResult.ReusedDurableSuccess -> JsonEnvelopeParser.requireObject(result.body, "OpenRouter tool response")
            is MissionDispatchResult.Reconciled -> {
                if (result.responseContent != null) {
                    JsonEnvelopeParser.requireObject(result.responseContent, "Reconciled OpenRouter response")
                } else {
                    JSONObject().put("status", "SUCCESS_RECONCILED")
                }
            }
            else -> throw ReconciliationException(result)
        }
    }

    private class ReconciliationException(val result: MissionDispatchResult) : Exception("Reconciliation prevented dispatch.")

    private suspend fun executeStructuredWithFallback(
        apiKey: String,
        strict: Pair<JSONObject, ProviderResponseAttribution>,
        jsonMode: Pair<JSONObject, ProviderResponseAttribution>,
        plain: Pair<JSONObject, ProviderResponseAttribution>,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
        maxAttempts: Int = 3,
    ): RawAgentResponse {
        var response = runCatching { 
            executeJsonRequest(apiKey, strict.first, strict.second, generation, requestContext, maxAttempts, wireVariantKind = ProviderWireVariantKind.STRICT_SCHEMA) 
        }.recoverCatching { strictError ->
            if (strictError is TerminalPersistenceException || strictError is CancellationException || strictError is ReconciliationException) throw strictError
            if (!strictError.isStructuredOutputUnsupported()) throw strictError
            executeJsonRequest(apiKey, jsonMode.first, jsonMode.second, generation, requestContext, maxAttempts, wireVariantKind = ProviderWireVariantKind.JSON_OBJECT)
        }
        .recoverCatching { jsonModeError ->
            if (jsonModeError is TerminalPersistenceException || jsonModeError is CancellationException || jsonModeError is ReconciliationException) throw jsonModeError
            if (!jsonModeError.isStructuredOutputUnsupported()) throw jsonModeError
            executeJsonRequest(apiKey, plain.first, plain.second, generation, requestContext, maxAttempts, wireVariantKind = ProviderWireVariantKind.PLAIN_JSON)
        }
        .getOrThrow()

        if (response.summary.finishReason == "length") {
            response = handleLengthFinishRecovery(apiKey, response, generation, requestContext)
        }
        return response
    }

    private suspend fun handleLengthFinishRecovery(
        apiKey: String,
        original: RawAgentResponse,
        generation: Int,
        requestContext: ProviderRequestContext.Mission,
    ): RawAgentResponse {
        val isPotentiallyUsable = original.content.trim().endsWith("}") || original.content.contains("\"work_product\":")
        if (isPotentiallyUsable) {
            val root = runCatching { JSONObject(original.content) }.getOrNull()
            if (root != null && root.has("work_product") && root.has("claims")) {
                 return original
            }
        }

        val prompt = buildString {
            appendLine("The previous response was truncated due to output length limits.")
            appendLine("Continue the structured response EXACTLY where it left off. Do not repeat the preamble or already completed fields.")
            appendLine("Ensure all remaining required fields (especially 'claims' and 'unresolved_questions') are complete and valid JSON.")
            appendLine()
            appendLine("TRUNCATED CONTENT:")
            appendLine(original.content.takeLast(2000))
        }
        
        val payload = basePayload(
            modelId = original.summary.resolvedModel ?: AUTO_BETA_ROUTER_MODEL_ID,
            systemPrompt = "You are continuing a truncated structured response.",
            userPrompt = prompt,
            role = requestContext.role,
            selectionReason = "length_continuation",
            freeOnly = false
        )
        
        return try {
            val continuation = executeJsonRequest(
                apiKey = apiKey,
                payload = payload.first,
                attribution = payload.second,
                generation = generation,
                requestContext = requestContext.forChildOperation(
                    MissionOperation.LENGTH_CONTINUATION,
                    requestContext.role ?: AgentTaskRole.PRIMARY_REASONING,
                    taskId = requestContext.taskId
                )
            )
            original.copy(
                content = original.content + continuation.content,
                summary = original.summary.merge(continuation.summary)
            )
        } catch (e: Exception) {
            original
        }
    }

    internal suspend fun executeJsonRequest(
        apiKey: String,
        payload: JSONObject,
        attribution: ProviderResponseAttribution,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
        maxAttempts: Int = 3,
        wireVariantKind: ProviderWireVariantKind = ProviderWireVariantKind.PRIMARY,
        wireVariantOrdinal: Int = 0
    ): RawAgentResponse {
        val startedAt = System.currentTimeMillis()
        val result = executeCapturedOpenRouterBody(apiKey, payload, attribution, "agent_structured_chat", generation, requestContext, maxAttempts, wireVariantKind, wireVariantOrdinal)
        return when (result) {
            is MissionDispatchResult.Success -> parseResponse(result.body, apiKey, attribution, result.statusCode, System.currentTimeMillis() - startedAt, result.exchangeId, requestContext.goalId, requestContext.taskId)
            is MissionDispatchResult.ReusedDurableSuccess -> parseResponse(result.body, apiKey, attribution, 200, 0L, result.exchangeId, requestContext.goalId, requestContext.taskId)
            is MissionDispatchResult.Reconciled -> {
                if (result.responseContent != null) {
                    parseResponse(result.responseContent, apiKey, attribution, 200, 0L, result.exchangeId, requestContext.goalId, requestContext.taskId)
                } else {
                    RawAgentResponse(
                        content = "SUCCESS_RECONCILED",
                        summary = result.summary ?: AgentApiSummary(responseId = "reconciled-${result.exchangeId}", httpStatusCode = 200),
                        sources = emptyList(),
                        reconciledProposal = result.proposal,
                        reconciledSummary = result.summary
                    )
                }
            }
            else -> throw ReconciliationException(result)
        }
    }

    data class ExchangeResolution(
        val outcome: ExchangeOutcome,
        val statusCode: Int? = null,
        val failureClass: String? = null,
        val safeDiagnosticSummary: String? = null,
        val providerResponseId: String? = null,
        val proposal: RecoveryProposal? = null,
        val summary: AgentApiSummary? = null,
        val rawBody: String? = null,
    )

    private fun handleTerminalTransition(
        requestContext: ProviderRequestContext.Mission,
        exchangeId: String,
        resolution: ExchangeResolution,
    ) {
        val requiredStore = store ?: throw OpenRouterException(
            statusCode = null,
            userMessage = "AgentStore is mandatory for autonomous mission requests [op=${requestContext.operation}, parentOp=${requestContext.parentOperationId}]",
            failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
        )
        terminalHook?.onTerminalTransition(requestContext.goalId, exchangeId, requestContext.parentOperationId, resolution.outcome)

        val result = requiredStore.transitionExchangeOutcomeWithResultAtomic(
            goalId = requestContext.goalId,
            exchangeId = exchangeId,
            newOutcome = resolution.outcome,
            context = requestContext,
            proposal = resolution.proposal,
            summary = resolution.summary,
            statusCode = resolution.statusCode,
            failureClass = resolution.failureClass,
            safeDiagnosticSummary = resolution.safeDiagnosticSummary,
            providerResponseId = resolution.providerResponseId,
            responseContent = resolution.rawBody,
        )

        when (result) {
            is TransitionOutcomeResult.Updated -> {
                diagnosticsRecord("exchange_terminal_updated", mapOf("exchangeId" to exchangeId, "outcome" to resolution.outcome.name))
            }
            is TransitionOutcomeResult.AlreadyTerminal -> {
                if (result.outcome == resolution.outcome) {
                    diagnosticsRecord("exchange_already_terminal_idempotent", mapOf("exchangeId" to exchangeId, "outcome" to resolution.outcome.name))
                } else {
                    diagnosticsRecord("exchange_already_terminal_conflict", mapOf("exchangeId" to exchangeId, "existingOutcome" to result.outcome.name, "attemptedOutcome" to resolution.outcome.name))
                    throw TerminalPersistenceException(
                        goalId = requestContext.goalId,
                        taskId = requestContext.taskId,
                        exchangeId = exchangeId,
                        parentOperationId = requestContext.parentOperationId,
                        operation = requestContext.operation.operationName,
                        intendedOutcome = resolution.outcome,
                        storeFailure = "ConflictingAlreadyTerminal",
                        safeReason = "existing=${result.outcome}, attempted=${resolution.outcome}",
                    )
                }
            }
            is TransitionOutcomeResult.StorageFailure -> {
                diagnostics?.error(
                    event = "handle_terminal_transition_storage_failure",
                    component = "provider",
                    throwable = result.cause,
                    fields = mapOf("exchange_id" to exchangeId)
                )
                throw TerminalPersistenceException(
                    goalId = requestContext.goalId,
                    taskId = requestContext.taskId,
                    exchangeId = exchangeId,
                    parentOperationId = requestContext.parentOperationId,
                    operation = requestContext.operation.operationName,
                    intendedOutcome = resolution.outcome,
                    storeFailure = "StorageFailure",
                    safeReason = result.cause.message ?: result.cause.javaClass.simpleName,
                )
            }
            is TransitionOutcomeResult.GoalMissing -> {
                throw TerminalPersistenceException(
                    goalId = requestContext.goalId,
                    taskId = requestContext.taskId,
                    exchangeId = exchangeId,
                    parentOperationId = requestContext.parentOperationId,
                    operation = requestContext.operation.operationName,
                    intendedOutcome = resolution.outcome,
                    storeFailure = "GoalMissing",
                    safeReason = "Goal ${requestContext.goalId} not found in store",
                )
            }
            is TransitionOutcomeResult.ExchangeMissing -> {
                throw TerminalPersistenceException(
                    goalId = requestContext.goalId,
                    taskId = requestContext.taskId,
                    exchangeId = exchangeId,
                    parentOperationId = requestContext.parentOperationId,
                    operation = requestContext.operation.operationName,
                    intendedOutcome = resolution.outcome,
                    storeFailure = "ExchangeMissing",
                    safeReason = result.message,
                )
            }
            is TransitionOutcomeResult.InvalidGeneration -> {
                throw TerminalPersistenceException(
                    goalId = requestContext.goalId,
                    taskId = requestContext.taskId,
                    exchangeId = exchangeId,
                    parentOperationId = requestContext.parentOperationId,
                    operation = requestContext.operation.operationName,
                    intendedOutcome = resolution.outcome,
                    storeFailure = "InvalidGeneration",
                    safeReason = "expected=${result.expected}, actual=${result.actual}",
                )
            }
            is TransitionOutcomeResult.InvalidLeaseOrGoalState -> {
                throw TerminalPersistenceException(
                    goalId = requestContext.goalId,
                    taskId = requestContext.taskId,
                    exchangeId = exchangeId,
                    parentOperationId = requestContext.parentOperationId,
                    operation = requestContext.operation.operationName,
                    intendedOutcome = resolution.outcome,
                    storeFailure = "InvalidLeaseOrGoalState",
                    safeReason = "Lease or goal state invariant violated during terminalization",
                )
            }
        }
    }

    internal sealed interface MissionDispatchResult {
        data class Success(val body: String, val statusCode: Int, val exchangeId: String) : MissionDispatchResult
        data class ReusedDurableSuccess(val body: String, val exchangeId: String) : MissionDispatchResult
        data class Reconciled(val proposal: RecoveryProposal?, val summary: AgentApiSummary?, val exchangeId: String, val responseContent: String? = null) : MissionDispatchResult
        data class ExistingNotDispatched(val attempt: ProviderRequestAttempt) : MissionDispatchResult
        data class ExistingInFlight(val attempt: ProviderRequestAttempt) : MissionDispatchResult
        data class ExistingAmbiguous(val attempt: ProviderRequestAttempt) : MissionDispatchResult
        data class ExistingTerminalFailure(val attempt: ProviderRequestAttempt) : MissionDispatchResult
        data class RetryAuthorizationRequired(val previousAttempt: ProviderRequestAttempt) : MissionDispatchResult
        data class LogicalIdentityConflict(val existingFingerprint: String, val requestedFingerprint: String) : MissionDispatchResult
        data class OwnershipRejected(val reason: String) : MissionDispatchResult
        data class StorageFailure(val cause: Throwable) : MissionDispatchResult
    }

    private suspend fun executeCapturedOpenRouterBody(
        apiKey: String,
        canonicalPayload: JSONObject,
        attribution: ProviderResponseAttribution,
        operationName: String,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
        maxAttempts: Int = 3,
        wireVariantKind: ProviderWireVariantKind = ProviderWireVariantKind.PRIMARY,
        wireVariantOrdinal: Int = 0
    ): MissionDispatchResult {
        val store = store ?: throw OpenRouterException(null, "AgentStore is mandatory for mission requests.")
        val operation = MissionOperation.fromName(operationName) ?: requestContext.operation
        val logicalRequestId = requestContext.logicalRequestId ?: requestContext.parentOperationId

        // 1. Centralized Wire Preparation
        val preparedRequest = prepareOpenRouterWireRequest(
            canonicalPayload = canonicalPayload,
            requestContext = requestContext,
            responseAttribution = attribution,
            wireVariantKind = wireVariantKind,
            wireVariantOrdinal = wireVariantOrdinal
        )
        
        var currentAttemptOrdinal = 1
        while (currentAttemptOrdinal <= maxAttempts) {
            if (isCancelled) {
                throw CancellationException("Mission cancelled before network dispatch")
            }

            // 2. Authoritative Reconciliation at the Dispatch Boundary
            val reconciliation = store.claimOrReconcileProviderRequestAtomic(
                goalId = requestContext.goalId,
                logicalRequestId = logicalRequestId,
                operation = operation,
                payloadFingerprint = preparedRequest.logicalPayloadFingerprint,
                ticket = requestContext.toTicket(requestContext.acquiredAt),
                role = requestContext.role,
                recoveryPlanId = requestContext.recoveryPlanId,
                wirePayloadFingerprint = preparedRequest.wirePayloadFingerprint,
                wireVariantKind = wireVariantKind,
                wireVariantOrdinal = wireVariantOrdinal,
                fingerprintSchemaVersion = 2
            )
            
            val attemptRecord = when (reconciliation) {
                is ReconciliationResult.NewDispatchClaimed -> reconciliation.attempt
                is ReconciliationResult.RetryDispatchClaimed -> reconciliation.attempt
                is ReconciliationResult.ExistingNotDispatched -> reconciliation.attempt
                is ReconciliationResult.ExistingSuccessfulResultAvailable -> {
                    return MissionDispatchResult.Reconciled(reconciliation.proposal, reconciliation.summary, reconciliation.attempt.exchangeId, reconciliation.responseContent)
                }
                is ReconciliationResult.ExistingActive, is ReconciliationResult.ExistingInFlight -> {
                    throw OpenRouterException(null, "Existing active request owned by another worker or session.")
                }
                is ReconciliationResult.ExistingAmbiguous -> {
                    throw OpenRouterException(null, "Operation delivery is ambiguous; zero-replay policy prevents automatic dispatch.")
                }
                is ReconciliationResult.ExistingSuccessfulResultMissing -> {
                    throw OpenRouterException(null, "Successful provider response received but proposal not persisted; manual reconciliation required.")
                }
                is ReconciliationResult.ExistingTerminalFailure -> {
                    throw OpenRouterException(null, "Operation has already reached a terminal failure state.")
                }
                is ReconciliationResult.RetryAuthorizationRequired -> {
                    throw OpenRouterException(null, "Retry requires explicit authorization.")
                }
                is ReconciliationResult.LogicalIdentityConflict -> {
                    throw OpenRouterException(null, "Logical request ID conflict: ${reconciliation.existingFingerprint} != ${reconciliation.requestedFingerprint}")
                }
                is ReconciliationResult.StaleOwnership -> {
                    throw OpenRouterException(null, "Stale ownership: generation mismatch.")
                }
                is ReconciliationResult.OwnershipMismatch -> {
                    throw OpenRouterException(null, "Ownership mismatch.")
                }
                is ReconciliationResult.RecoveryPlanMismatch -> {
                    throw OpenRouterException(null, "Recovery plan mismatch: ${reconciliation.actualPlanId} != ${reconciliation.expectedPlanId}")
                }
                is ReconciliationResult.GoalTerminal -> {
                    throw OpenRouterException(null, "Goal is in a terminal state: ${reconciliation.status}")
                }
                is ReconciliationResult.GoalMissing -> {
                    throw OpenRouterException(null, "GoalMissing.")
                }
                is ReconciliationResult.StorageFailure -> {
                    throw OpenRouterException(null, "Storage failure during reconciliation: ${reconciliation.cause.message}")
                }
            }

            val exchangeId = attemptRecord.exchangeId
            val startedAt = System.currentTimeMillis()
            val targetSessionId = currentSessionId ?: researchMonitor?.status()?.sessionId
            
            val tracker = TransportTracker()
            val request = Request.Builder()
                .url(CHAT_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .header("X-OpenRouter-Metadata", "enabled")
                .header("User-Agent", "OpenAssistant-Android/${BuildConfig.VERSION_NAME}")
                .post(preparedRequest.wirePayloadText.toRequestBody(JSON_MEDIA_TYPE))
                .tag(TransportTracker::class.java, tracker)
                .tag(ProviderTransportContext::class.java, ProviderTransportContext(requestContext.goalId, exchangeId))
                .build()
            
            val call = missionClient.newCall(request)
            activeCalls += call

            try {
                // 3. Outbound Validation (Already done in prepareOpenRouterWireRequest, but we can verify invariants)
                postActiveHook?.afterActivePersisted(requestContext.goalId, exchangeId)
                
                if (isCancelled) {
                    throw CancellationException("Mission cancelled before network dispatch")
                }

                val requestBytes = preparedRequest.wirePayloadText.toByteArray(Charsets.UTF_8).size
                diagnostics?.info(
                    event = "provider_request_dispatched",
                    component = "provider",
                    fields = mapOf(
                        "exchange_id" to exchangeId,
                        "goal_id" to requestContext.goalId,
                        "task_id" to requestContext.taskId,
                        "operation" to operation.operationName,
                        "requested_model" to preparedRequest.wirePayload.optString("model"),
                        "request_bytes" to requestBytes,
                        "wire_fingerprint" to preparedRequest.wirePayloadFingerprint,
                        "variant" to wireVariantKind.name,
                        "variant_ordinal" to wireVariantOrdinal
                    )
                )

                researchMonitor?.record(
                    category = "provider",
                    event = "request",
                    correlationId = exchangeId,
                    targetSessionId = targetSessionId,
                    fields = mapOf(
                        "provider" to "OpenRouter",
                        "operation" to "${operation.operationName} (wire attempt ${attemptRecord.wireAttemptOrdinal})",
                        "method" to "POST",
                        "endpoint" to CHAT_URL,
                        "requested_model" to preparedRequest.wirePayload.optString("model"),
                        "request_bytes" to requestBytes,
                    ),
                )
                
                emitDetailedContentPreviews(exchangeId, preparedRequest.wirePayload, requestContext)

                val responseBody = call.execute().use { response ->
                    val rawBody = response.body.string()
                    val choiceError = runCatching { extractEmbeddedChoiceError(rawBody) }.getOrNull()
                    val semanticSuccess = response.isSuccessful && choiceError == null
                    val parsedRoot = runCatching { JSONObject(rawBody) }.getOrNull()
                    val providerRespId = parsedRoot?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
                    
                    val (proposalToPersist, summaryToPersist) = if (semanticSuccess && requestContext.recoveryPlanId != null) {
                        val p = runCatching { 
                            if (operation == MissionOperation.RECOVERY_PROPOSAL) parseRecoveryProposal(rawBody) else null 
                        }.getOrNull()
                        p to null
                    } else null to null

                    val resolution = if (semanticSuccess) {
                        ExchangeResolution(
                            outcome = ExchangeOutcome.RESPONSE_SUCCESS, 
                            statusCode = response.code, 
                            providerResponseId = providerRespId,
                            proposal = proposalToPersist,
                            summary = summaryToPersist,
                            rawBody = rawBody
                        )
                    } else {
                        ExchangeResolution(
                            outcome = ExchangeOutcome.RESPONSE_ERROR,
                            statusCode = response.code,
                            failureClass = choiceError ?: "HTTP_${response.code}",
                            providerResponseId = providerRespId
                        )
                    }
                    
                    handleTerminalTransition(requestContext, exchangeId, resolution)

                    researchMonitor?.record(
                        category = "provider",
                        event = "response",
                        level = if (semanticSuccess) "INFO" else "ERROR",
                        correlationId = exchangeId,
                        targetSessionId = targetSessionId,
                        fields = buildMap {
                            put("provider", "OpenRouter")
                            put("operation", operation.operationName)
                            put("http_status", response.code)
                            put("successful", semanticSuccess)
                            put("duration_ms", System.currentTimeMillis() - startedAt)
                        },
                    )

                    if (!response.isSuccessful) throw response.toException(rawBody, apiKey)
                    return MissionDispatchResult.Success(rawBody, response.code, exchangeId)
                }
            } catch (error: Throwable) {
                if (error is TerminalPersistenceException) throw error
                
                val isCancellationTimeout = error.message?.contains("CANCELLATION_TIMEOUT") == true || 
                    error.cause?.message?.contains("CANCELLATION_TIMEOUT") == true
                val isRealCancellation = isCancelled || error is CancellationException || isCancellationTimeout
                
                val resolution = ExchangeResolution(
                    outcome = when {
                        isCancellationTimeout -> ExchangeOutcome.CANCELLATION_TIMEOUT
                        isRealCancellation -> ExchangeOutcome.CANCELLED
                        else -> ExchangeOutcome.TRANSPORT_FAILURE
                    },
                    failureClass = error::class.java.simpleName,
                )
                handleTerminalTransition(requestContext, exchangeId, resolution)
                
                if (isRealCancellation) throw CancellationException("Mission cancelled during dispatch")
                
                // Selective internal retry: deterministic matrix
                val isRetryable = tracker.stage < ProviderTransportStage.REQUEST_BODY_STARTED && (error is IOException || (error is OpenRouterException && error.statusCode?.let { it == 429 || it >= 500 } == true))
                
                if (isRetryable && currentAttemptOrdinal < maxAttempts) {
                    val auth = ProviderRetryAuthorization(
                        logicalRequestId = logicalRequestId,
                        payloadFingerprint = preparedRequest.logicalPayloadFingerprint,
                        executionGeneration = requestContext.executionGeneration,
                        previousExchangeId = exchangeId,
                        failureClass = error::class.java.simpleName,
                        deliveryCertainty = tracker.certainty,
                        attemptOrdinal = currentAttemptOrdinal + 1,
                        wirePayloadFingerprint = preparedRequest.wirePayloadFingerprint,
                        fingerprintSchemaVersion = 2,
                        wireVariantKind = wireVariantKind,
                        wireVariantOrdinal = wireVariantOrdinal
                    )
                    store.authorizeRetry(requestContext.goalId, auth)
                    currentAttemptOrdinal++
                    continue
                }
                
                throw error
            } finally {
                activeCalls -= call
            }
        }
        throw OpenRouterException(null, "The provider request failed after internal repairs.")
    }

    private fun validateOutboundRequest(payload: JSONObject) {
        val model = payload.optString("model")
        require(model.isNotBlank()) { "Outbound request is missing 'model'." }
        
        // 1. Enforce strict allowlist for autonomous requests
        val allowlist = setOf(AUTO_BETA_ROUTER_MODEL_ID, FREE_ROUTER_MODEL_ID, BODY_BUILDER_MODEL_ID)
        require(model in allowlist || model.endsWith(":free")) { 
            "Unauthorized model for autonomous request: $model. Must use auto-beta, free, or bodybuilder routers." 
        }
        payload.optJSONArray("models")?.let { models ->
            for (i in 0 until models.length()) {
                val m = models.optString(i)
                require(m in allowlist || m.endsWith(":free")) { "Unauthorized fallback model: $m" }
                require(m != BODY_BUILDER_MODEL_ID) { "Body Builder must never appear in a fallback models array." }
            }
        }

        val messages = payload.optJSONArray("messages")
        require(messages != null && messages.length() > 0) { "Outbound request is missing 'messages'." }
        
        // 2. Validate message roles and content shapes
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            require(role in setOf("system", "user", "assistant", "tool")) { "Invalid message role: $role at index $i" }
            
            val content = msg.opt("content")
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    val type = part.optString("type")
                    require(type in setOf("text", "image_url")) { "Invalid content part type: $type at message $i part $j" }
                    if (type == "image_url") {
                        val urlObj = part.optJSONObject("image_url")
                        require(urlObj != null && urlObj.has("url")) { "Missing image URL in message $i part $j" }
                    }
                }
            } else if (content != null && content != JSONObject.NULL) {
                require(content is String) { "Message content must be string or array at message $i." }
            }
        }

        // 3. Reasoning must be object for OpenRouter to avoid HTTP 400.
        if (payload.has("reasoning")) {
            val reasoning = payload.opt("reasoning")
            require(reasoning is JSONObject) { "The top-level 'reasoning' property must be a JSON object, not a string." }
            require(reasoning.has("effort")) { "The 'reasoning' object is missing the required 'effort' field." }
        }
        
        // 4. Tools and response format
        val tools = payload.optJSONArray("tools")
        if (tools != null) {
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val type = tool.optString("type")
                if (type.startsWith("openrouter:")) {
                    require(type in CORE_SERVER_TOOL_TYPES || type == "openrouter:subagent" || type == "openrouter:advisor" || type == "openrouter:fusion") {
                        "Unsupported OpenRouter server tool: $type"
                    }
                    val parameters = tool.optJSONObject("parameters")
                    if (parameters != null && parameters.has("reasoning")) {
                        require(parameters.opt("reasoning") is JSONObject) { "Server tool reasoning parameters must be an object." }
                    }
                } else {
                    require(type == "function") { "Unsupported tool type: $type" }
                }
            }
        }
        
        payload.optJSONObject("response_format")?.let { fmt ->
            val type = fmt.optString("type")
            require(type in setOf("text", "json_object", "json_schema")) { "Invalid response_format type: $type" }
            if (type == "json_schema") {
                require(fmt.has("json_schema")) { "Missing 'json_schema' object in response_format." }
            }
        }

        // 5. Diagnostic markers check: No diagnostic redaction markers allowed on the wire.
        fun checkRedactionMarkers(value: Any?) {
            when (value) {
                is String -> {
                    require(!value.contains("[REDACTED]") && !value.contains("[EXCLUDED]")) {
                        "Outbound request contains a diagnostic redaction marker: '$value'. This indicates a failure in payload decoupling."
                    }
                }
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        checkRedactionMarkers(value.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        checkRedactionMarkers(value.opt(i))
                    }
                }
            }
        }
        checkRedactionMarkers(payload)
        
        // 6. Credential check
        val payloadStr = payload.toString()
        require(!payloadStr.contains("sk-or-") && !payloadStr.contains("Bearer ")) {
            "Outbound request payload contains credential-like patterns. Raw secrets must never enter protocol fields."
        }

        // 7. Data Leakage: Ensure no local-only fields leaked.
        require(!payload.has("local_metadata")) { "Outbound payload contains internal 'local_metadata' field." }
        
        // 8. Request size limit (approx 4MB for safety)
        require(payloadStr.length < 4_000_000) { "Request payload too large (${payloadStr.length} chars)." }
    }

    private fun repairReasoningShape(payload: JSONObject): Boolean {
        var repaired = false
        fun repair(obj: JSONObject) {
            if (obj.has("reasoning") && obj.opt("reasoning") is String) {
                val raw = obj.optString("reasoning")
                val effort = when {
                    raw.isBlank() || raw.contains("EXCLUDED", ignoreCase = true) -> "medium"
                    raw.equals("high", ignoreCase = true) -> "high"
                    raw.equals("low", ignoreCase = true) -> "low"
                    else -> "medium"
                }
                obj.put("reasoning", JSONObject().put("effort", effort))
                repaired = true
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                if (value is JSONObject) repair(value)
                else if (value is JSONArray) {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) repair(item)
                    }
                }
            }
        }
        repair(payload)
        return repaired
    }

    private fun diagnosticsRecord(event: String, fields: Map<String, Any?>) {
        researchMonitor?.record(
            category = "runtime",
            event = event,
            level = "INFO",
            targetSessionId = currentSessionId,
            fields = fields
        )
    }

    private fun missionContextPrompt(goal: AgentGoal): String = buildString {
        if (goal.refinements.isNotEmpty()) {
            appendLine()
            appendLine("USER REFINEMENTS (Apply these additional directions):")
            goal.refinements.forEach { appendLine("- $it") }
        }
        if (goal.confirmedConstraints.isNotEmpty()) {
            appendLine()
            appendLine("CONFIRMED CONSTRAINTS:")
            goal.confirmedConstraints.forEach { appendLine("- $it") }
        }
        if (goal.inferredPreferences.isNotEmpty()) {
            appendLine()
            appendLine("INFERRED PREFERENCES:")
            goal.inferredPreferences.forEach { appendLine("- $it") }
        }
        if (goal.unresolvedQuestions.isNotEmpty()) {
            appendLine()
            appendLine("UNRESOLVED QUESTIONS:")
            goal.unresolvedQuestions.forEach { appendLine("- $it") }
        }
        if (goal.evidenceRequirements.isNotEmpty()) {
            appendLine()
            appendLine("EVIDENCE REQUIREMENTS:")
            goal.evidenceRequirements.forEach { appendLine("- $it") }
        }
        if (goal.preferredSourceTypes.isNotEmpty()) {
            appendLine()
            appendLine("PREFERRED SOURCE TYPES:")
            goal.preferredSourceTypes.forEach { appendLine("- $it") }
        }
        goal.freshnessRequirement?.let {
            appendLine()
            appendLine("FRESHNESS REQUIREMENT: $it")
        }
        if (goal.exclusions.isNotEmpty()) {
            appendLine()
            appendLine("EXCLUSIONS (Do not use these sources or keywords):")
            goal.exclusions.forEach { appendLine("- $it") }
        }
        if (goal.finalOutputDescription.isNotBlank()) {
            appendLine()
            appendLine("DESIRED DELIVERABLE: ${goal.finalOutputDescription}")
        }
    }

    private fun parseResponse(
        body: String,
        apiKey: String,
        attribution: ProviderResponseAttribution,
        statusCode: Int,
        durationMs: Long,
        exchangeId: String,
        goalId: String? = null,
        taskId: String? = null
    ): RawAgentResponse {
        val root = JsonEnvelopeParser.requireObject(body, "OpenRouter agent response")
        val usage = root.optJSONObject("usage")
        val role = attribution.role
        val selectionReason = attribution.selectionReason
        
        val provider = root.optJSONObject("openrouter_metadata")?.optString("provider")
            ?: root.optString("provider").takeIf { it.isNotBlank() && it != "null" }
            
        val choices = root.optJSONArray("choices")
        val choice = choices?.optJSONObject(0)
        val finishReason = choice?.optString("finish_reason")
        val nativeFinishReason = choice?.optJSONObject("openrouter_metadata")?.optString("native_finish_reason")
            ?: root.optJSONObject("openrouter_metadata")?.optString("native_finish_reason")

        val responseSummary = AgentApiSummary(
            responseId = root.optString("id").takeIf { it.isNotBlank() && it != "null" },
            resolvedModel = root.optString("model").takeIf { it.isNotBlank() && it != "null" },
            role = role,
            selectionReason = selectionReason,
            provider = provider,
            finishReason = finishReason,
            nativeFinishReason = nativeFinishReason,
            httpStatusCode = statusCode,
            promptTokens = usage.optIntOrNull("prompt_tokens") ?: usage.optIntOrNull("input_tokens"),
            completionTokens = usage.optIntOrNull("completion_tokens") ?: usage.optIntOrNull("output_tokens"),
            totalTokens = usage.optIntOrNull("total_tokens"),
            costUsd = usage.optDoubleOrNull("cost"),
            webSearchRequests = providerWebSearchRequestCount(usage).takeIf { it > 0 },
            durationMs = durationMs
        )
        root.optJSONObject("error")?.let { error ->
            throw OpenRouterException(
                error.optInt("code").takeIf { it > 0 },
                com.david.openassistant.data.openrouter.SecretRedactor.redact(error.providerErrorMessage(), apiKey),
            ).withAgentUsage(responseSummary)
        }
        if (choice == null) {
            throw OpenRouterException(null, "The agent model returned no response choice.")
                .withAgentUsage(responseSummary)
        }

        val choiceError = choice.optJSONObject("error")
        val message = choice.optJSONObject("message")
            ?: throw OpenRouterException(null, "The agent model returned an invalid response message.")
                .withAgentUsage(responseSummary)
        val content = JsonEnvelopeParser.messageText(message)
        
        val providerMessage = choiceError?.providerErrorMessage() ?: content
        
        // V36: Stop scanning assistant content for 429. Use HTTP status or structured provider error ONLY.
        val isRateLimit = (statusCode == 429) || (choiceError != null && (
            providerMessage != null && (
                providerMessage.contains("rate limit", ignoreCase = true) || 
                providerMessage.contains("temporarily rate-limited", ignoreCase = true) ||
                providerMessage.contains("resource exhausted", ignoreCase = true) ||
                providerMessage.contains("too many requests", ignoreCase = true)
            )
        ))

        if (choiceError != null || finishReason == "error" || isRateLimit) {
            val code = if (isRateLimit) 429 else (choiceError?.optInt("code")?.takeIf { it > 0 } ?: statusCode)
            val userMsg = providerMessage ?: "The selected model returned a choice-level error."
            throw OpenRouterException(
                statusCode = code,
                userMessage = com.david.openassistant.data.openrouter.SecretRedactor.redact(userMsg, apiKey),
            ).withAgentUsage(responseSummary)
        }
        val toolCalls = message.optJSONArray("tool_calls")
        val hasNoOutput = content.isNullOrBlank() && (toolCalls == null || toolCalls.length() == 0)

        if (hasNoOutput) {
            val diagnosis = buildString {
                append("The agent model returned no usable text or tool calls.")
                finishReason?.let { append(" Finish reason: $it.") }
                nativeFinishReason?.let { append(" Native finish reason: $it.") }
                if (role == AgentTaskRole.PRIMARY_REASONING) {
                    append(" This is often caused by a model spending its entire budget on internal reasoning or an incompatible output limit.")
                }
            }
            throw OpenRouterException(null, diagnosis)
                .withAgentUsage(responseSummary)
        }

        val nonNullContent = content ?: ""
        
        // V36: Detailed Content Capture for provider answer
        val monitorStatus = researchMonitor?.status()
        if (monitorStatus?.detailedContentCaptureEnabled == true) {
            diagnostics?.contentPreview(
                kind = "provider_answer",
                content = nonNullContent,
                goalId = goalId,
                taskId = taskId,
                exchangeId = responseSummary.responseId,
                extraFields = mapOf(
                    "role" to role?.name,
                    "finish_reason" to finishReason,
                    "capture_sid" to monitorStatus.captureSessionId
                )
            )
        }

        // V34: Semantic classification of successful transport responses
        val semanticOutcome = when {
            nonNullContent.isBlank() && (toolCalls == null || toolCalls.length() == 0) -> ExchangeOutcome.UNUSABLE_EMPTY_RESPONSE
            nonNullContent.isNotBlank() && nonNullContent.trim().isEmpty() -> ExchangeOutcome.UNUSABLE_WHITESPACE_RESPONSE
            else -> ExchangeOutcome.USABLE_STRUCTURED_RESULT
        }
        
        diagnostics?.info(
            event = "provider_response_classified",
            component = "provider",
            fields = mapOf(
                "exchange_id" to exchangeId,
                "provider_response_id" to responseSummary.responseId,
                "http_status" to statusCode,
                "semantic_outcome" to semanticOutcome.name,
                "duration_ms" to responseSummary.durationMs
            )
        )

        if (semanticOutcome != ExchangeOutcome.USABLE_STRUCTURED_RESULT) {
            throw OpenRouterException(
                statusCode = 200,
                userMessage = "The model returned a semantically unusable successful response ($semanticOutcome).",
            ).withAgentUsage(responseSummary.copy(httpStatusCode = 200))
        }

        val sources = (
            parseSourceCitations(message.optJSONArray("annotations")) +
                recoverHttpsSourceCitations(nonNullContent)
            ).distinctBy { it.url }.take(MAX_SOURCE_CITATIONS)
        return RawAgentResponse(
            content = nonNullContent,
            summary = responseSummary,
            sources = sources,
            toolExecutions = providerResearchToolExecutions(root, sources),
        )
    }

    private fun parseToolSourceCitations(outputJson: String): List<AgentSourceCitation> {
        val root = runCatching {
            JsonEnvelopeParser.requireObject(outputJson, "Local tool result")
        }.getOrNull() ?: return emptyList()
        val sources = root.optJSONArray("sources") ?: return emptyList()
        val seen = mutableSetOf<String>()
        return buildList {
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                val url = source.optString("url").trim()
                if (!url.startsWith("https://") || !seen.add(url)) continue
                add(
                    AgentSourceCitation(
                        title = source.optString("title").trim().ifBlank { url }.take(MAX_SOURCE_TITLE_CHARS),
                        url = url.take(MAX_SOURCE_URL_CHARS),
                        excerpt = source.optString("excerpt").trim()
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?.take(MAX_SOURCE_EXCERPT_CHARS),
                    ).sanitizedForPersistence(),
                )
            }
        }
    }

    private fun normalizedToolCallSignature(call: com.david.openassistant.domain.tools.OpenRouterToolCall): String {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
        if (args == null) return "${call.name}:${call.argumentsJson.trim()}"
        val keys = args.keys().asSequence().sorted().toList()
        val normalizedArgs = keys.joinToString("|") { key ->
            val value = args.optString(key).trim()
            when {
                call.name == "public_web_fetch" && key == "url" -> ResearchQualityGate.canonicalSourceUrl(value)
                call.name == "public_web_search" && key == "query" -> value.lowercase(Locale.US)
                else -> value
            }
        }
        return "${call.name}:$normalizedArgs"
    }

    private fun isFreeOnlyModel(modelId: String): Boolean =
        modelId.equals(FREE_ROUTER_MODEL_ID, ignoreCase = true) || modelId.endsWith(":free", ignoreCase = true)

    private fun extractEmbeddedChoiceError(body: String): String? = runCatching {
        val json = JsonEnvelopeParser.requireEmbeddedObject(body, "OpenRouter choice error check")
        val rootError = json.optJSONObject("error")?.providerErrorMessage()
        if (rootError != null) return rootError

        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val choiceError = choice?.optJSONObject("error")
        val finishReason = choice?.optString("finish_reason")
        val message = choiceError?.providerErrorMessage() 
            ?: if (finishReason == "error") "The selected model returned a choice-level error." else null
        
        // Enhance: detect upstream rate limits in message text if code is missing
        if (message != null && (message.contains("rate limit", ignoreCase = true) || message.contains("temporarily rate-limited", ignoreCase = true))) {
            return message
        }
        message
    }.getOrNull()

    private fun parseSourceCitations(annotations: JSONArray?): List<AgentSourceCitation> {
        if (annotations == null) return emptyList()
        val seenUrls = mutableSetOf<String>()
        return buildList {
            for (index in 0 until annotations.length()) {
                val annotation = annotations.optJSONObject(index) ?: continue
                val citation = annotation.optJSONObject("url_citation")
                    ?: annotation.optJSONObject("citation")
                    ?: annotation
                val url = citation.optString("url").trim()
                if (!url.startsWith("https://") || !seenUrls.add(url)) continue
                val title = citation.optString("title").trim().ifBlank { url }
                val excerpt = citation.optString("content").trim()
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.take(MAX_SOURCE_EXCERPT_CHARS)
                add(
                    AgentSourceCitation(
                        title = title.take(MAX_SOURCE_TITLE_CHARS),
                        url = url.take(MAX_SOURCE_URL_CHARS),
                        excerpt = excerpt,
                    ).sanitizedForPersistence(),
                )
                if (size >= MAX_SOURCE_CITATIONS) break
            }
        }
    }

    /**
     * Uses openrouter/bodybuilder to design or repair a complex request.
     * The generated JSON is strictly validated and normalized before execution.
     */
    suspend fun buildComplexRequest(
        apiKey: String,
        instructions: String,
        context: String,
        generation: Int = 0,
        requestContext: ProviderRequestContext.Mission,
    ): JSONObject {
        val prompt = buildString {
            appendLine("Lead investigator: repair or design one high-intelligence OpenRouter chat completion request for this milestone.")
            appendLine("Instructions: $instructions")
            appendLine("Context: $context")
            appendLine()
            appendLine("Return a single object containing exactly one proposal in the 'requests' array: { \"requests\": [ { ... } ] }.")
            appendLine("Use only standard fields and allowed models (openrouter/auto-beta or openrouter/free).")
            appendLine("Do not include markdown, explanation, or recursion.")
        }
        val response = executeStructuredWithFallback(
            apiKey = apiKey,
            strict = basePayload(
                modelId = BODY_BUILDER_MODEL_ID,
                systemPrompt = "You are a request design utility for a deep-research runtime. You output exactly one valid OpenRouter request proposal in a top-level 'requests' array.",
                userPrompt = prompt,
                role = AgentTaskRole.REQUEST_CONSTRUCTION,
                selectionReason = "request_design",
                freeOnly = false // Body builder is always paid
            ).let { (p, attr) ->
                p.put("temperature", 0.1)
                p to attr
            },
            jsonMode = basePayload(
                modelId = BODY_BUILDER_MODEL_ID,
                systemPrompt = "You are a request design utility.",
                userPrompt = prompt,
                role = AgentTaskRole.REQUEST_CONSTRUCTION,
                selectionReason = "request_design_json",
                freeOnly = false
            ).let { (p, attr) ->
                p.put("response_format", JSONObject().put("type", "json_object"))
                p.put("temperature", 0.1)
                p to attr
            },
            plain = basePayload(
                modelId = BODY_BUILDER_MODEL_ID,
                systemPrompt = "You are a request design utility.",
                userPrompt = prompt,
                role = AgentTaskRole.REQUEST_CONSTRUCTION,
                selectionReason = "request_design_plain",
                freeOnly = false
            ).let { (p, attr) ->
                p.put("temperature", 0.1)
                p to attr
            },
            generation = generation,
            requestContext = requestContext,
        )
        val root = JsonEnvelopeParser.requireEmbeddedObject(response.content, "Body Builder output")
        val requests = root.optJSONArray("requests")
        if (requests == null || requests.length() == 0) {
            // Fallback to the root if it looks like a direct request object
            if (root.has("messages") || root.has("model")) {
                return validateAndNormalizeGeneratedRequest(root)
            }
            throw OpenRouterException(null, "Body Builder returned no request proposals.")
        }
        if (requests.length() > 1) {
            throw OpenRouterException(null, "Body Builder returned multiple request proposals; exactly one is required for production execution.")
        }
        val proposal = requests.optJSONObject(0)
            ?: throw OpenRouterException(null, "Body Builder returned an invalid request proposal.")
            
        return validateAndNormalizeGeneratedRequest(proposal)
    }

    private fun validateAndNormalizeGeneratedRequest(request: JSONObject): JSONObject {
        val normalized = JSONObject()
        val allowedModels = setOf(AUTO_BETA_ROUTER_MODEL_ID, FREE_ROUTER_MODEL_ID)
        
        // 1. Force allowed model for the final execution
        val model = request.optString("model")
        normalized.put("model", if (model in allowedModels) model else AUTO_BETA_ROUTER_MODEL_ID)
        
        // 2. Approved fields only; strip auth, plugins, and custom headers
        val approvedFields = setOf("messages", "tools", "tool_choice", "response_format", "temperature", "max_tokens", "stop", "seed", "top_p", "frequency_penalty", "presence_penalty")
        approvedFields.forEach { field ->
            if (request.has(field)) {
                val value = request.opt(field)
                // Basic recursion and credential check for nested strings
                if (value?.toString()?.contains(BODY_BUILDER_MODEL_ID) == true || value?.toString()?.contains("Authorization") == true) {
                    return@forEach 
                }
                normalized.put(field, value)
            }
        }
        
        // 3. Security: No recursion (Body Builder cannot call Body Builder)
        if (normalized.optString("model") == BODY_BUILDER_MODEL_ID) {
            normalized.put("model", AUTO_BETA_ROUTER_MODEL_ID)
        }

        // 4. Validate tools: only standard or authorized function tools
        val tools = normalized.optJSONArray("tools")
        if (tools != null) {
            val validTools = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val type = tool.optString("type")
                if (type == "function" || (type.startsWith("openrouter:") && type != "openrouter:bodybuilder")) {
                    validTools.put(tool)
                }
            }
            if (validTools.length() > 0) normalized.put("tools", validTools) else normalized.remove("tools")
        }

        // 5. Mandatory fields check: OpenRouter requires "messages" or "prompt".
        // Automatic agent work strictly uses the "messages" array format.
        val messages = normalized.optJSONArray("messages")
        if (messages == null || messages.length() == 0) {
            val error = "Generated request is missing mandatory 'messages' array."
            researchMonitor?.record(
                category = "agent",
                event = "request_normalization_failed",
                level = "ERROR",
                fields = mapOf(
                    "error" to error,
                    "original_request_keys" to request.keys().asSequence().toList().toString(),
                    "normalized_request_keys" to normalized.keys().asSequence().toList().toString(),
                )
            )
            throw OpenRouterException(null, error)
        }
        
        // Ensure messages are not too large
        if (normalized.toString().length > 128_000) {
            throw OpenRouterException(null, "Generated request exceeds the maximum safe local-normalization size.")
        }
        
        return normalized
    }

    private fun mapRoleToModelId(role: AgentTaskRole, freeOnly: Boolean = false): String = when (role) {
        AgentTaskRole.PRIMARY_REASONING -> if (freeOnly) FREE_ROUTER_MODEL_ID else AUTO_BETA_ROUTER_MODEL_ID
        AgentTaskRole.ECONOMICAL_RESEARCH -> FREE_ROUTER_MODEL_ID
        AgentTaskRole.REQUEST_CONSTRUCTION -> BODY_BUILDER_MODEL_ID
    }

    private fun determineTaskRole(task: AgentTask, modelId: String): AgentTaskRole {
        if (modelId == BODY_BUILDER_MODEL_ID) return AgentTaskRole.REQUEST_CONSTRUCTION
        if (task.capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT, AgentCapability.VERIFY)) return AgentTaskRole.PRIMARY_REASONING
        if (modelId == FREE_ROUTER_MODEL_ID) return AgentTaskRole.ECONOMICAL_RESEARCH
        return AgentTaskRole.PRIMARY_REASONING
    }

    private fun prepareOpenRouterWireRequest(
        canonicalPayload: JSONObject,
        requestContext: ProviderRequestContext,
        responseAttribution: ProviderResponseAttribution,
        wireVariantKind: ProviderWireVariantKind = ProviderWireVariantKind.PRIMARY,
        wireVariantOrdinal: Int = 0
    ): PreparedOpenRouterRequest {
        // 1. Reproduce legacy logical fingerprint exactly for reconciliation
        val legacyPayload = JSONObject(canonicalPayload.toString())
        if (requestContext is ProviderRequestContext.Mission) {
            legacyPayload.put("metadata", JSONObject()
                .put("agent_role", responseAttribution.role?.name)
                .put("selection_reason", responseAttribution.selectionReason)
                .put("goal_id", requestContext.goalId)
                .put("task_id", requestContext.taskId)
            )
        }
        val logicalFingerprint = OpenRouterProtocolUtils.computePayloadFingerprint(legacyPayload.toString())

        // 2. Prepare final wire payload (defensive copy)
        val wirePayload = JSONObject(canonicalPayload.toString())
        
        // Ensure no internal protocol-level keys leaked into top-level
        val keysToRemove = listOf(
            "metadata", "local_metadata", "goal_id", "task_id",
            "agent_role", "selection_reason", "logical_request_id",
            "recovery_plan_id", "exchange_id"
        )
        keysToRemove.forEach { wirePayload.remove(it) }

        // 3. Validate final wire payload
        OpenRouterProtocolUtils.validateOutboundRequest(wirePayload)

        // 4. Serialize exactly once
        val wirePayloadText = wirePayload.toString()

        // 5. Compute wire fingerprint from the final transmitted bytes
        val wirePayloadFingerprint = OpenRouterProtocolUtils.computePayloadFingerprint(wirePayloadText)

        return PreparedOpenRouterRequest(
            wirePayload = wirePayload,
            wirePayloadText = wirePayloadText,
            wirePayloadFingerprint = wirePayloadFingerprint,
            logicalPayloadFingerprint = logicalFingerprint,
            requestContext = requestContext,
            responseAttribution = responseAttribution,
            wireVariantKind = wireVariantKind,
            wireVariantOrdinal = wireVariantOrdinal
        )
    }

    private fun basePayload(
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        reasoningEffort: String? = null,
        role: AgentTaskRole? = null,
        selectionReason: String? = null,
        freeOnly: Boolean = false,
    ): Pair<JSONObject, ProviderResponseAttribution> {
        val allowlist = setOf(AUTO_BETA_ROUTER_MODEL_ID, FREE_ROUTER_MODEL_ID, BODY_BUILDER_MODEL_ID)
        
        // Enforce strict three-system allowlist for all automatic requests.
        // Body Builder must never appear inside the normal models fallback array.
        val primary = when {
            modelId in allowlist -> modelId
            role != null -> mapRoleToModelId(role, freeOnly)
            else -> if (freeOnly) FREE_ROUTER_MODEL_ID else AUTO_BETA_ROUTER_MODEL_ID
        }

        val candidates = mutableListOf(primary)
        if (!freeOnly && primary == AUTO_BETA_ROUTER_MODEL_ID) {
            candidates.add(FREE_ROUTER_MODEL_ID)
        } else if (primary == FREE_ROUTER_MODEL_ID && !freeOnly && role != AgentTaskRole.ECONOMICAL_RESEARCH) {
            // Allow escalation from free if not strictly economical research and NOT free-only
            candidates.add(0, AUTO_BETA_ROUTER_MODEL_ID)
        }

        if (freeOnly) {
            // Hard enforce: remove anything that isn't the free router or a :free model
            candidates.retainAll { it == FREE_ROUTER_MODEL_ID || it.endsWith(":free") }
            if (candidates.isEmpty()) candidates.add(FREE_ROUTER_MODEL_ID)
        }
        
        val finalCandidates = candidates.distinct().take(2)
        val finalModel = finalCandidates.first()
        val finalFallbacks = finalCandidates.drop(1)

        // Final safety guard for the wire payload
        if (freeOnly) {
            require(finalModel == FREE_ROUTER_MODEL_ID || finalModel.endsWith(":free")) {
                "FREE_ROUTING_VIOLATION: primary model '$finalModel' is not allowed."
            }
            finalFallbacks.forEach { fallback ->
                require(fallback == FREE_ROUTER_MODEL_ID || fallback.endsWith(":free")) {
                    "FREE_ROUTING_VIOLATION: fallback model '$fallback' is not allowed."
                }
            }
        }

        val payload = applyAgentCompletionLimit(JSONObject())
            .put("model", finalModel)
            .apply {
                if (finalFallbacks.isNotEmpty()) {
                    // OpenRouter: 'models' should be the prioritized fallback list.
                    // We include the primary at the front if the provider treats it as the full set,
                    // but OpenRouter typically tries 'model' then 'models' in order.
                    // For maximum compatibility with OpenRouter's routing logic, we send the fallbacks only.
                    put("models", JSONArray(finalFallbacks))
                }
            }
            .put("stream", false)
            .apply {
                reasoningEffort?.let { effort ->
                    put("reasoning", JSONObject().put("effort", effort))
                }
            }
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "$systemPrompt\n${providerTemporalContext()}"),
                    )
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
        
        return payload to ProviderResponseAttribution(role = role, selectionReason = selectionReason)
    }

    private fun jsonSchemaResponseFormat(name: String, schema: JSONObject): JSONObject =
        JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", name)
                    .put("strict", true)
                    .put("schema", schema),
            )

    private fun criterionSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("id", JSONObject().put("type", "string"))
                .put("description", JSONObject().put("type", "string"))
                .put("weight", JSONObject().put("type", "number").put("minimum", 0.1).put("maximum", 10.0)),
        )
        .put("required", JSONArray(listOf("id", "description", "weight")))
        .put("additionalProperties", false)

    private fun checkSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("criterion_id", JSONObject().put("type", "string"))
                .put(
                    "status",
                    JSONObject().put("type", "string").put(
                        "enum",
                        JSONArray(listOf("pass", "partial", "fail", "not_evaluated")),
                    ),
                )
                .put("score", JSONObject().put("type", "number").put("minimum", 0.0).put("maximum", 1.0))
                .put("explanation", JSONObject().put("type", "string")),
        )
        .put("required", JSONArray(listOf("criterion_id", "status", "score", "explanation")))
        .put("additionalProperties", false)

    private fun researchStrategySchema(minimumQueries: Int): JSONObject {
        val stringArray = { minimum: Int ->
            JSONObject()
                .put("type", "array")
                .put("minItems", minimum)
                .put("maxItems", 12)
                .put("items", JSONObject().put("type", "string").put("minLength", 1))
        }
        val querySchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("query", JSONObject().put("type", "string").put("minLength", 3))
                    .put("purpose", JSONObject().put("type", "string").put("minLength", 8))
                    .put("expected_evidence", JSONObject().put("type", "string").put("minLength", 8))
                    .put(
                        "depends_on_discovery",
                        JSONObject().put("type", JSONArray(listOf("string", "null"))),
                    ),
            )
            .put(
                "required",
                JSONArray(listOf("query", "purpose", "expected_evidence", "depends_on_discovery")),
            )
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("interpretation", JSONObject().put("type", "string").put("minLength", 20))
                    .put("decision_target", JSONObject().put("type", "string").put("minLength", 10))
                    .put("scope_ambiguities", stringArray(1))
                    .put("unknowns", stringArray(minimumQueries))
                    .put("evidence_targets", stringArray(minimumQueries))
                    .put("falsifiers", stringArray(2))
                    .put("follow_up_rule", JSONObject().put("type", "string").put("minLength", 20))
                    .put(
                        "queries",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", minimumQueries)
                            .put("maxItems", minimumQueries + 3)
                            .put("items", querySchema),
                    ),
            )
            .put(
                "required",
                JSONArray(
                    listOf(
                        "interpretation",
                        "decision_target",
                        "scope_ambiguities",
                        "unknowns",
                        "evidence_targets",
                        "falsifiers",
                        "follow_up_rule",
                        "queries",
                    ),
                ),
            )
            .put("additionalProperties", false)
    }

    private fun searchRefinementSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "query",
                JSONObject().put("type", "string").put("description", "Refined search query."),
            ),
        )
        .put("required", JSONArray().put("query"))
        .put("additionalProperties", false)

    private fun evidenceDrivenFollowUpSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("query", JSONObject().put("type", "string").put("minLength", 3))
                .put("purpose", JSONObject().put("type", "string").put("minLength", 8))
                .put("expected_evidence", JSONObject().put("type", "string").put("minLength", 8))
                .put("depends_on_discovery", JSONObject().put("type", "string").put("minLength", 8)),
        )
        .put(
            "required",
            JSONArray(listOf("query", "purpose", "expected_evidence", "depends_on_discovery")),
        )
        .put("additionalProperties", false)

    private fun planSchema(): JSONObject {
        val taskSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string"))
                    .put("title", JSONObject().put("type", "string"))
                    .put("instructions", JSONObject().put("type", "string"))
                    .put(
                        "capability",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(listOf("reason", "deep_research", "tool_use", "tool_create", "synthesize"))),
                    )
                    .put(
                        "depends_on",
                        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                    )
                    .put("weight", JSONObject().put("type", "number").put("minimum", 0.1).put("maximum", 10.0))
                    .put(
                        "acceptance_criteria",
                        JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 8).put("items", criterionSchema()),
                    ),
            )
            .put(
                "required",
                JSONArray(listOf("id", "title", "instructions", "capability", "depends_on", "weight", "acceptance_criteria")),
            )
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("objective", JSONObject().put("type", "string"))
                    .put("final_output", JSONObject().put("type", "string"))
                    .put(
                        "acceptance_criteria",
                        JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 10).put("items", criterionSchema()),
                    )
                    .put(
                        "tasks",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("items", taskSchema),
                    ),
            )
            .put("required", JSONArray(listOf("title", "objective", "final_output", "acceptance_criteria", "tasks")))
            .put("additionalProperties", false)
    }

    private fun stepSchema(task: AgentTask? = null): JSONObject {
        val minimumWorkProductChars = when (task?.capability) {
            AgentCapability.DEEP_RESEARCH -> ResearchQualityGate.MIN_DEEP_RESEARCH_CONTENT_CHARS
            AgentCapability.WEB_RESEARCH -> ResearchQualityGate.MIN_STANDARD_RESEARCH_CONTENT_CHARS
            AgentCapability.SYNTHESIZE -> ResearchQualityGate.MIN_SYNTHESIS_CONTENT_CHARS
            AgentCapability.CORRECT -> ResearchQualityGate.MIN_CORRECTION_CONTENT_CHARS
            else -> 0
        }
        val minimumClaims = when (task?.capability) {
            AgentCapability.DEEP_RESEARCH -> ResearchQualityGate.MIN_DEEP_RESEARCH_FACTS
            AgentCapability.WEB_RESEARCH -> 1
            AgentCapability.SYNTHESIZE -> ResearchQualityGate.MIN_SYNTHESIS_CLAIMS
            AgentCapability.CORRECT -> ResearchQualityGate.MIN_CORRECTION_CLAIMS
            else -> 0
        }
        val workProductSchema = JSONObject().put("type", "string").apply {
            if (minimumWorkProductChars > 0) put("minLength", minimumWorkProductChars)
        }
        val claimSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string"))
                    .put("text", JSONObject().put("type", "string"))
                    .put(
                        "type",
                        JSONObject().put("type", "string").put(
                            "enum",
                            JSONArray(listOf("fact", "inference", "recommendation", "uncertainty")),
                        ),
                    )
                    .put("confidence", JSONObject().put("type", "number").put("minimum", 0.0).put("maximum", 1.0))
                    .put(
                        "supporting_evidence_ids",
                        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                    )
                    .put(
                        "source_urls",
                        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                    ),
            )
            .put(
                "required",
                JSONArray(listOf("id", "text", "type", "confidence", "supporting_evidence_ids", "source_urls")),
            )
            .put("additionalProperties", false)
        val claimsSchema = JSONObject()
            .put("type", "array")
            .put("maxItems", 30)
            .put("items", claimSchema)
            .apply { if (minimumClaims > 0) put("minItems", minimumClaims) }
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("work_product", workProductSchema)
                    .put("completion_score", JSONObject().put("type", "number").put("minimum", 0.0).put("maximum", 1.0))
                    .put("acceptance_checks", JSONObject().put("type", "array").put("items", checkSchema()))
                    .put("claims", claimsSchema)
                    .put(
                        "unresolved_questions",
                        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                    ),
            )
            .put(
                "required",
                JSONArray(listOf("work_product", "completion_score", "acceptance_checks", "claims", "unresolved_questions")),
            )
            .put("additionalProperties", false)
    }

    private fun verificationSchema(): JSONObject {
        val claimReviewSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("claim_id", JSONObject().put("type", "string"))
                    .put(
                        "support",
                        JSONObject().put("type", "string").put(
                            "enum",
                            JSONArray(listOf("supported", "partial", "unsupported", "contradicted")),
                        ),
                    )
                    .put("explanation", JSONObject().put("type", "string")),
            )
            .put("required", JSONArray(listOf("claim_id", "support", "explanation")))
            .put("additionalProperties", false)
        val conceptSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("name", JSONObject().put("type", "string"))
                    .put("definition", JSONObject().put("type", "string"))
                    .put("trigger_pattern", JSONObject().put("type", "string"))
                    .put("expected_benefit", JSONObject().put("type", "string"))
                    .put("risks", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                    .put(
                        "validation_tests",
                        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                    ),
            )
            .put(
                "required",
                JSONArray(listOf("name", "definition", "trigger_pattern", "expected_benefit", "risks", "validation_tests")),
            )
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("passed", JSONObject().put("type", "boolean"))
                    .put("quality_score", JSONObject().put("type", "number").put("minimum", 0.0).put("maximum", 1.0))
                    .put("summary", JSONObject().put("type", "string"))
                    .put(
                        "missing_requirements",
                        JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                    )
                    .put("acceptance_checks", JSONObject().put("type", "array").put("items", checkSchema()))
                    .put("claim_reviews", JSONObject().put("type", "array").put("items", claimReviewSchema))
                    .put(
                        "correction_instructions",
                        JSONObject().put("type", JSONArray(listOf("string", "null"))),
                    )
                    .put("final_answer", JSONObject().put("type", "string"))
                    .put(
                        "concept_candidates",
                        JSONObject().put("type", "array").put("maxItems", 3).put("items", conceptSchema),
                    ),
            )
            .put(
                "required",
                JSONArray(
                    listOf(
                        "passed",
                        "quality_score",
                        "summary",
                        "missing_requirements",
                        "acceptance_checks",
                        "claim_reviews",
                        "correction_instructions",
                        "final_answer",
                        "concept_candidates",
                    ),
                ),
            )
            .put("additionalProperties", false)
    }

    private fun parsePlan(
        content: String,
        requireSynthesis: Boolean = true,
    ): AgentPlanDraft {
        val root = JsonEnvelopeParser.requireEmbeddedObject(content, "Agent plan")
        val rawTasks = root.getJSONArray("tasks")
        val taskObjects = buildList {
            for (index in 0 until rawTasks.length()) {
                when (val item = rawTasks.opt(index)) {
                    is JSONObject -> add(item)
                    is JSONArray -> {
                        for (nestedIndex in 0 until item.length()) {
                            item.optJSONObject(nestedIndex)?.let(::add)
                        }
                    }
                }
            }
        }
        require(taskObjects.isNotEmpty()) { "The plan must contain at least one task object." }
        val goalCriteria = parseCriteria(root.optJSONArray("acceptance_criteria"), "goal")
        val drafts = buildList {
            val seenIds = mutableSetOf<String>()
            for ((index, raw) in taskObjects.withIndex()) {
                val id = sanitizeId(raw.optString("id"), "step_${index + 1}")
                require(seenIds.add(id)) { "Duplicate task id: $id" }
                
                val rawCap = raw.optString("capability")
                val title = raw.optString("title").trim().take(120)
                val instructions = raw.optString("instructions").trim().take(2_000)
                
                val capability = if (rawCap.isNotBlank()) {
                    AgentCapability.fromWireName(rawCap)
                } else {
                    recoverCapabilityFromContext(id, title, instructions, index, taskObjects.size)
                }
                
                require(
                    capability in setOf(
                        AgentCapability.REASON,
                        AgentCapability.DEEP_RESEARCH,
                        AgentCapability.TOOL_USE,
                        AgentCapability.TOOL_CREATE,
                        AgentCapability.SYNTHESIZE,
                        AgentCapability.VERIFY,
                        AgentCapability.CORRECT,
                    ),
                )
                require(title.isNotBlank()) { "Task $id has no request-specific title." }
                require(instructions.isNotBlank()) { "Task $id has no request-specific instructions." }
                add(
                    AgentTaskDraft(
                        id = id,
                        title = title,
                        instructions = instructions,
                        capability = capability,
                        dependsOn = raw.optJSONArray("depends_on").toStringList(),
                        weight = raw.optDouble("weight", 1.0).coerceIn(0.1, 10.0),
                        acceptanceCriteria = parseCriteria(raw.optJSONArray("acceptance_criteria"), id),
                    ),
                )
            }
        }
        val normalized = drafts.mapIndexed { index, task ->
            val earlierIds = drafts.take(index).mapTo(mutableSetOf()) { it.id }
            val dependencies = task.dependsOn
                .filter { it in earlierIds }
                .distinct()
                .ifEmpty { if (index == 0) emptyList() else listOf(drafts[index - 1].id) }
            task.copy(dependsOn = dependencies)
        }
        if (requireSynthesis) {
            require(normalized.any { it.capability == AgentCapability.SYNTHESIZE }) {
                "A request-specific synthesis milestone is required; no generic fallback will be inserted."
            }
        }
        require(goalCriteria.isNotEmpty()) {
            "A request-specific plan must contain measurable goal acceptance criteria."
        }
        val title = root.getString("title").trim().take(120)
        val objective = root.getString("objective").trim().take(1_000)
        val finalOutput = root.getString("final_output").trim().take(1_000)
        require(title.isNotBlank() && objective.isNotBlank() && finalOutput.isNotBlank()) {
            "A request-specific plan must define its title, objective, and final output."
        }
        return AgentPlanDraft(
            title = title,
            objective = objective,
            finalOutputDescription = finalOutput,
            acceptanceCriteria = goalCriteria,
            tasks = normalized,
        )
    }

    private fun parseCriteria(array: JSONArray?, prefix: String): List<AgentAcceptanceCriterion> {
        val seen = mutableSetOf<String>()
        return responseJsonList(array) { raw, index ->
            val id = sanitizeId(raw.optString("id"), "${prefix}_criterion_${index + 1}")
                .let { base -> generateSequence(base) { previous -> "${previous}_x" }.first(seen::add) }
            AgentAcceptanceCriterion(
                id = id,
                description = raw.optString("description").trim().take(1_000),
                weight = raw.optDouble("weight", 1.0).coerceIn(0.1, 10.0),
            )
        }.filter { it.description.isNotBlank() }.take(10)
    }

    private fun parseAcceptanceChecks(
        array: JSONArray?,
        criteria: List<AgentAcceptanceCriterion>,
    ): List<AgentAcceptanceCheck> {
        if (criteria.isEmpty()) return emptyList()
        val validIds = criteria.mapTo(mutableSetOf()) { it.id }
        val parsed = responseJsonList(array) { raw, _ ->
            AgentAcceptanceCheck(
                criterionId = raw.optString("criterion_id"),
                status = AgentAcceptanceCheckStatus.fromWireName(raw.optString("status")),
                score = raw.optDouble("score", 0.0).coerceIn(0.0, 1.0),
                explanation = raw.optString("explanation").trim().take(1_000),
            )
        }.filter { it.criterionId in validIds }.associateBy { it.criterionId }
        return criteria.map { criterion ->
            parsed[criterion.id] ?: AgentAcceptanceCheck(
                criterionId = criterion.id,
                status = AgentAcceptanceCheckStatus.NOT_EVALUATED,
                score = 0.0,
                explanation = "This criterion was not evaluated in the structured response.",
            )
        }
    }

    private fun weightedCheckScore(
        criteria: List<AgentAcceptanceCriterion>,
        checks: List<AgentAcceptanceCheck>,
    ): Double {
        if (criteria.isEmpty()) return 1.0
        val checksById = checks.associateBy { it.criterionId }
        val totalWeight = criteria.sumOf { it.weight.coerceAtLeast(0.1) }
        return criteria.sumOf { criterion ->
            criterion.weight.coerceAtLeast(0.1) * (checksById[criterion.id]?.score ?: 0.0)
        } / totalWeight.coerceAtLeast(0.1)
    }

    private fun determineClaimSupport(
        type: AgentClaimType,
        evidenceIds: List<String>,
        sourceUrls: List<String>,
        sourceBackedEvidenceIds: Set<String>,
    ): AgentClaimSupport = when {
        type == AgentClaimType.ORIGINAL_HYPOTHESIS -> AgentClaimSupport.PARTIAL
        type == AgentClaimType.UNCERTAINTY -> AgentClaimSupport.SUPPORTED
        sourceUrls.isNotEmpty() -> AgentClaimSupport.SUPPORTED
        type == AgentClaimType.FACT && evidenceIds.any(sourceBackedEvidenceIds::contains) -> AgentClaimSupport.SUPPORTED
        evidenceIds.isNotEmpty() && type != AgentClaimType.FACT -> AgentClaimSupport.SUPPORTED
        evidenceIds.isNotEmpty() -> AgentClaimSupport.PARTIAL
        type == AgentClaimType.RECOMMENDATION -> AgentClaimSupport.PARTIAL
        else -> AgentClaimSupport.UNSUPPORTED
    }

    private fun RawAgentResponse.mergeRepair(repair: RawAgentResponse): RawAgentResponse = RawAgentResponse(
        content = repair.content,
        summary = summary.merge(repair.summary),
        sources = (sources + repair.sources).distinctBy { it.url }.take(MAX_SOURCE_CITATIONS),
        toolExecutions = toolExecutions + repair.toolExecutions,
        queryFingerprints = (queryFingerprints + repair.queryFingerprints).distinct(),
        rejectedQueries = (rejectedQueries + repair.rejectedQueries).distinctBy { it.canonicalFingerprint.ifBlank { it.originalQuery } },
        verifiedUrls = verifiedUrls + repair.verifiedUrls
    )

    private fun RawAgentResponse.withResearchBootstrap(bootstrap: ResearchBootstrap): RawAgentResponse = copy(
        summary = summary.merge(bootstrap.summary),
        sources = (bootstrap.sources + sources).distinctBy { it.url }.take(MAX_SOURCE_CITATIONS),
        toolExecutions = bootstrap.executions + toolExecutions,
        queryFingerprints = (queryFingerprints + bootstrap.queryFingerprints).distinct(),
        rejectedQueries = (rejectedQueries + bootstrap.rejectedQueries).distinctBy { it.canonicalFingerprint.ifBlank { it.originalQuery } },
        verifiedUrls = verifiedUrls + bootstrap.verifiedUrls
    )

    private fun RawAgentResponse.withRecoveredInlineSources(allowedUrls: Set<String>): RawAgentResponse {
        val found = recoverHttpsSourceCitations(content)
        val verified = found.filter { it.url in allowedUrls }
        found.filter { it.url !in allowedUrls }.forEach { rejected ->
            diagnostics?.warning(
                event = "fabricated_source_rejected",
                component = "research",
                fields = mapOf("url" to rejected.url, "reason" to "model_fabricated_unverified")
            )
        }
        return copy(
            sources = (sources + verified)
                .distinctBy { it.url }
                .take(MAX_SOURCE_CITATIONS),
        )
    }

    private fun AgentStepResult.needsResearchMetadataRepair(task: AgentTask, goal: AgentGoal): StructureRepairReason? {
        if (task.capability !in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH, AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)) return null
        if (content.isBlank()) return null
        
        if (task.capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)) {
            val decision = ResearchQualityGate.evaluateStep(task, this, goal)
            if (!decision.passed) {
                val reasonStr = decision.reasons.joinToString()
                if (reasonStr.contains("too little publication-ready analysis")) return StructureRepairReason.INSUFFICIENT_CONTENT
                if (reasonStr.contains("produced") && reasonStr.contains("structured claim(s); at least")) return StructureRepairReason.INSUFFICIENT_CLAIMS
                if (reasonStr.contains("grounded") && reasonStr.contains("claim(s) in preserved evidence IDs")) return StructureRepairReason.INVALID_PROVENANCE
                if (reasonStr.contains("produced no grounded factual claim")) return StructureRepairReason.NO_SUPPORTED_FACTUAL_CLAIM
                if (reasonStr.contains("must pass every acceptance criterion")) return StructureRepairReason.ACCEPTANCE_CRITERIA_INCOMPLETE
                return StructureRepairReason.NO_SUPPORTED_FACTUAL_CLAIM
            }
        }
        
        val missingFactualClaims = claims.none { it.type == AgentClaimType.FACT }
        val criteriaWereNotEvaluated = task.acceptanceCriteria.isNotEmpty() &&
            acceptanceChecks.all { it.status == AgentAcceptanceCheckStatus.NOT_EVALUATED }
            
        if (missingFactualClaims) return StructureRepairReason.NO_SUPPORTED_FACTUAL_CLAIM
        if (criteriaWereNotEvaluated) return StructureRepairReason.ACCEPTANCE_CRITERIA_INCOMPLETE
        return null
    }

    private fun AgentApiSummary.merge(other: AgentApiSummary): AgentApiSummary = AgentApiSummary(
        responseId = other.responseId ?: responseId,
        resolvedModel = other.resolvedModel ?: resolvedModel,
        role = other.role ?: role,
        selectionReason = other.selectionReason ?: selectionReason,
        previousRoute = other.previousRoute ?: previousRoute,
        cooldownState = other.cooldownState ?: cooldownState,
        provider = other.provider ?: provider,
        finishReason = other.finishReason ?: finishReason,
        nativeFinishReason = other.nativeFinishReason ?: nativeFinishReason,
        httpStatusCode = other.httpStatusCode ?: httpStatusCode,
        promptTokens = nullableSum(promptTokens, other.promptTokens),
        completionTokens = nullableSum(completionTokens, other.completionTokens),
        totalTokens = nullableSum(totalTokens, other.totalTokens),
        costUsd = nullableSum(costUsd, other.costUsd),
        webSearchRequests = nullableSum(webSearchRequests, other.webSearchRequests),
        webFetchRequests = nullableSum(webFetchRequests, other.webFetchRequests),
        discoveredLeads = nullableSum(discoveredLeads, other.discoveredLeads),
        rabbitHoleIterations = nullableSum(rabbitHoleIterations, other.rabbitHoleIterations),
    )

    private fun nullableSum(first: Int?, second: Int?): Int? =
        if (first == null && second == null) null else (first ?: 0) + (second ?: 0)

    private fun nullableSum(first: Double?, second: Double?): Double? =
        if (first == null && second == null) null else (first ?: 0.0) + (second ?: 0.0)

    private fun sanitizeId(value: String, fallback: String): String =
        value.trim().ifBlank { fallback }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { fallback }

    private fun recoverCapabilityFromContext(
        id: String,
        title: String,
        instructions: String,
        index: Int,
        totalTasks: Int
    ): AgentCapability {
        val context = "$id $title $instructions".lowercase(Locale.US)
        return when {
            context.contains("discovery") || context.contains("primary") || context.contains("contradiction") || context.contains("gap closure") || context.contains("deep research") || context.contains("research") -> AgentCapability.DEEP_RESEARCH
            context.contains("synthesize") || context.contains("final answer") || context.contains("final report") || (index == totalTasks - 1 && totalTasks > 1) -> AgentCapability.SYNTHESIZE
            context.contains("verify") -> AgentCapability.VERIFY
            context.contains("correct") -> AgentCapability.CORRECT
            else -> throw IllegalArgumentException("Task $id capability is missing and ambiguous.")
        }
    }

    private fun Throwable.isStructuredOutputUnsupported(): Boolean {
        if (this !is OpenRouterException) return false
        return isStructuredOutputCompatibilityError(statusCode, userMessage)
    }

    private fun Throwable.isServerToolCompatibilityError(): Boolean {
        if (this !is OpenRouterException || statusCode !in setOf(400, 404, 422)) return false
        val normalized = userMessage.lowercase(Locale.US)
        return SERVER_TOOL_ERROR_MARKERS.any(normalized::contains)
    }

    private fun Throwable.isToolChoiceCompatibilityError(): Boolean {
        if (this !is OpenRouterException || statusCode !in setOf(400, 404, 422)) return false
        val normalized = userMessage.lowercase(Locale.US)
        return TOOL_CHOICE_ERROR_MARKERS.any(normalized::contains)
    }

    private fun Response.toException(body: String, apiKey: String): OpenRouterException {
        val message = runCatching {
            JsonEnvelopeParser.requireObject(body, "OpenRouter error response")
                .optJSONObject("error")
                ?.providerErrorMessage()
        }.getOrNull().orEmpty().ifBlank { "OpenRouter request failed with HTTP $code." }
        return OpenRouterException(code, SecretRedactor.redact(message, apiKey))
    }

    /** Pulls the useful upstream reason out of OpenRouter's generic wrapper. */
    private fun JSONObject.providerErrorMessage(): String {
        val primary = optString("message").trim().ifBlank { "OpenRouter returned an error." }
        if (!primary.isGenericProviderError()) return primary
        val metadata = optJSONObject("metadata")
        val errorType = metadata?.optString("error_type")?.takeIf(String::isNotBlank)
        val raw = metadata?.optString("raw").orEmpty().trim()
        val nestedMessage = raw.takeIf(String::isNotBlank)?.let { rawMessage ->
            runCatching {
                val nested = JsonEnvelopeParser.requireObject(rawMessage, "Provider error metadata")
                nested.optJSONObject("error")?.optString("message")
                    ?.takeIf(String::isNotBlank)
                    ?: nested.optString("message").takeIf(String::isNotBlank)
            }.getOrNull()
        }
        val readableRaw = raw
            .takeIf { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("[") }
            ?.take(800)
        return nestedMessage ?: errorType ?: readableRaw ?: primary
    }

    private fun String.isGenericProviderError(): Boolean {
        val normalized = trim().trimEnd('.').lowercase(Locale.US)
        return normalized in setOf(
            "provider returned error",
            "the provider returned an error",
            "openrouter returned an error",
            "upstream provider error",
            "json error injected into sse stream",
        )
    }

    private fun <T> responseJsonList(array: JSONArray?, transform: (JSONObject, Int) -> T): List<T> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { transform(item, index) }.getOrNull()?.let(::add)
            }
        }
    }

    private data class ResearchBootstrap(
        val context: String,
        val sources: List<AgentSourceCitation>,
        val executions: List<AgentToolExecution>,
        val summary: AgentApiSummary,
        val queryFingerprints: List<String> = emptyList(),
        val rejectedQueries: List<RejectedResearchQuery> = emptyList(),
        val verifiedUrls: Set<String> = emptySet(),
        val sourceReads: List<SourceRead> = emptyList(),
    ) {
        fun hasCompletedResearchToolWork(
            minimumSearches: Int,
            minimumFullReads: Int,
            minimumDomains: Int,
        ): Boolean {
            val searches = successfulResearchSearchCount(executions)
            val readUnits = successfulResearchReadAccounting(executions).equivalentReadUnits
            val sourceDomains = sources.mapNotNull { source ->
                runCatching { URI(source.url).host?.lowercase(Locale.US) }.getOrNull()
            }.distinct().size
            return searches >= minimumSearches &&
                readUnits >= minimumFullReads &&
                sources.size >= minimumFullReads &&
                sourceDomains >= minimumDomains
        }

        companion object {
            val EMPTY = ResearchBootstrap(
                context = "",
                sources = emptyList(),
                executions = emptyList(),
                summary = AgentApiSummary(),
            )
        }
    }

    internal data class RawAgentResponse(
        val content: String,
        val summary: AgentApiSummary,
        val sources: List<AgentSourceCitation>,
        val toolExecutions: List<AgentToolExecution> = emptyList(),
        val queryFingerprints: List<String> = emptyList(),
        val rejectedQueries: List<RejectedResearchQuery> = emptyList(),
        val verifiedUrls: Set<String> = emptySet(),
        val sourceReads: List<SourceRead> = emptyList(),
        val reconciledProposal: RecoveryProposal? = null,
        val reconciledSummary: AgentApiSummary? = null,
    )

    private fun emitDetailedContentPreviews(
        exchangeId: String,
        payload: JSONObject,
        context: ProviderRequestContext.Mission
    ) {
        val monitorStatus = researchMonitor?.status() ?: return
        if (!monitorStatus.detailedContentCaptureEnabled) return

        val messages = payload.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            val content = msg.optString("content")
            if (content.isNotBlank()) {
                diagnostics?.contentPreview(
                    kind = "provider_instruction",
                    content = content,
                    goalId = context.goalId,
                    taskId = context.taskId,
                    exchangeId = exchangeId,
                    extraFields = mapOf(
                        "role" to role, 
                        "index" to i,
                        "capture_sid" to monitorStatus.captureSessionId
                    )
                )
            }
        }
    }

    internal companion object {
        const val CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val MODELS_URL = "https://openrouter.ai/api/v1/models"
        const val AUTO_BETA_ROUTER_MODEL_ID = "openrouter/auto-beta"
        const val FREE_ROUTER_MODEL_ID = "openrouter/free"
        const val BODY_BUILDER_MODEL_ID = "openrouter/bodybuilder"
        const val MAX_PLANNER_REQUEST_CHARS = 32_000
        const val MAX_EXECUTOR_REQUEST_CHARS = 20_000
        const val MAX_VERIFIER_REQUEST_CHARS = 20_000
        const val MAX_OBJECTIVE_CHARS = 4_000
        const val MAX_FINAL_OUTPUT_DESCRIPTION_CHARS = 4_000
        const val MAX_EVIDENCE_CHARS_PER_ITEM = 5_000
        const val MAX_VERIFICATION_EVIDENCE_CHARS = 8_000
        const val MAX_VERIFICATION_SOURCE_CHARS = 4_000
        const val MAX_VERIFICATION_PROMPT_CHARS = 96_000
        const val MAX_VERIFICATION_CLAIMS_CHARS = 48_000
        const val MAX_VERIFICATION_CLAIM_LINE_CHARS = 2_500
        const val MAX_FINAL_ANSWER_CHARS = 32_000
        const val MAX_CLAIM_TEXT_CHARS = 1_500
        const val MAX_SOURCE_CITATIONS = 32
        const val MAX_SOURCE_TITLE_CHARS = 240
        const val MAX_SOURCE_URL_CHARS = 2_048
        const val MAX_SOURCE_EXCERPT_CHARS = 2_400
        const val MAX_REFRESHED_LOCAL_TOOL_DEFINITIONS = 16
        const val MAX_STRUCTURE_REPAIR_CHARS = 48_000
        const val MAX_RESEARCH_BOOTSTRAP_CHARS = 18_000
        const val MAX_RESEARCH_FETCH_CANDIDATES = 6
        const val MAX_FETCHED_PAGE_CONTEXT_CHARS = 5_000
        const val MAX_PRIOR_RESEARCH_LEADS = 8
        const val MAX_ADAPTIVE_STRATEGY_EVIDENCE_ITEMS = 8
        const val MAX_ADAPTIVE_STRATEGY_EVIDENCE_CHARS_PER_ITEM = 2_500
        const val MAX_ADAPTIVE_STRATEGY_PROMPT_CHARS = 28_000
        const val MAX_ADAPTIVE_STRATEGY_AUDIT_CHARS = 3_000
        const val MAX_RABBIT_HOLE_SOURCE_LEADS = 10
        const val MAX_RABBIT_HOLE_FULL_PAGES = 3
        const val MAX_RABBIT_HOLE_PAGE_CHARS = 4_000
        const val MAX_RABBIT_HOLE_CONTEXT_CHARS = 18_000
        const val MAX_SOURCE_VALIDATION_ENTITIES = 12
        const val LEGACY_WEB_PLUGIN_RESULTS = 10
        val PERMANENT_PUBLIC_FETCH_FAILURE_PATTERN = Regex(
            "\\b(?:HTTP\\s+(?:404|410)|PDF extraction is unavailable)\\b",
            RegexOption.IGNORE_CASE,
        )
        val CORE_SERVER_TOOL_TYPES = setOf(
            "openrouter:web_search",
            "openrouter:web_fetch",
            "openrouter:datetime",
        )
        val SEARCH_ONLY_SERVER_TOOL_TYPES = setOf("openrouter:web_search")
        val SERVER_TOOL_ERROR_MARKERS = listOf(
            "advisor",
            "fusion",
            "subagent",
            "web_fetch",
            "web fetch",
            "web_search",
            "web search",
            "datetime",
            "server tool",
            "unknown tool",
            "unsupported tool",
            "tool is not supported",
            "tools are not supported",
            "tool type",
            "no endpoints found that support",
        )
        val TOOL_CHOICE_ERROR_MARKERS = listOf(
            "tool_choice",
            "tool choice",
            "forced tool",
            "specific tool",
            "unsupported parameter",
        )
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val PLANNER_SYSTEM_PROMPT =
            "You are the semantic planning component of a deep-research runtime, not a chatbot. Think like a lead investigator: understand each request on its own terms and create a new investigation plan from its specific entities, definitions, ambiguities, unknowns, evidence needs, and answer-changing conditions. Do not copy a generic plan or query template. " +
                "Surrender is not an option; if a direct answer seems unavailable, plan forensic reconstruction, lateral pivots, and community-consensus checks. Require full-source reading, primary-source tracing, evidence-driven rabbit-hole follow-ups, contradiction testing, and gap closure. Never collapse a factual request into a quick answer. The application validates every plan."
        const val PLANNER_REFINEMENT_SYSTEM_PROMPT =
            "You repair inadequate deep-research plans by reasoning again from the exact request. Think laterally: if direct paths are blocked, find a different way around. Return a complete valid plan whose milestones and criteria are specific to that request. Structural research roles are quality controls, not reusable subject-matter templates. Do not answer the request or claim research was completed."
        const val RESEARCH_STRATEGY_SYSTEM_PROMPT =
            "You are the investigation architect for one pass of a durable deep-research system. Build a fresh semantic model of the exact request and available evidence, then derive distinct, executable searches from its real unknowns. Think like a detective: if direct entity searches fail, pivot to parent organizations, technical standards, or community hubs. Use advanced operators like site:reddit.com, site:stackoverflow.com, or \"exact match\" to bypass SEO noise. " +
                "Prioritize deep discovery over shallow summaries. If a search branch is blocked, reconstruct the information need from indirect evidence (sibling products, historical context, community consensus). Do not answer the question or fabricate findings."
        const val RESEARCH_STRATEGY_REFINEMENT_SYSTEM_PROMPT =
            "Repair an invalid investigation strategy by re-reasoning from the exact request and evidence. Produce distinct request-specific information needs and executable queries, not paraphrases or generic search modifiers. Be creative and relentless in your search angles. Do not answer the question or add research findings."
        const val EVIDENCE_DRIVEN_FOLLOW_UP_SYSTEM_PROMPT =
            "You generate one evidence-driven rabbit-hole branch for a deep-research runtime. Derive it from a concrete lead, citation, discrepancy, or 'community-verified' marker discovered in source material. " +
                "Actively seek 'proof-heavy' targets like official datasets, whitepapers, government archives, or source-code repositories. " +
                "Be a relentless detective: follow the money, the author, the version, or the mentioned standard. If a direct path is unsearchable, use lateral pivots to infer the answer. Do not restate the original request; pursue the discovered evidence trail until its logical conclusion. Return only the requested structured query metadata."
        const val EXECUTOR_SYSTEM_PROMPT =
            "You are a high-intelligence research assistant. Think like a combination of a research librarian and a private investigator: be relentless, thorough, and skeptical. Complete only the assigned research role, pursue useful leads beyond the first result, and triangulate answers from indirect evidence if direct facts are missing. Surrender is not an option. " +
                "When reading forums or social threads (Reddit, HN, StackOverflow), you MUST distinguish between official docs, community-verified solutions (look for 'accepted', 'resolved', 'upvoted'), and ongoing debates. " +
                "Extract dissenting voices to satisfy the contradiction gate. If you reach a wall, figure out a different angle—a different way around. A human research assistant would figure it out, and you must too. " +
                "Use supplied evidence, classify claims, be explicit about uncertainty, and cite preserved provenance precisely."
        const val STRUCTURE_REPAIR_SYSTEM_PROMPT =
            "You repair JSON serialization for an inspectable cognitive runtime. Preserve the supplied work exactly in substance, " +
                "add no new facts or actions, grade criteria conservatively, and return only the requested object."
        const val VERIFIER_SYSTEM_PROMPT =
            "You are an independent deep-research verifier. Reject shallow, first-result, snippet-only, incomplete, unsupported, contradictory, or nonresponsive work. Require evidence of query diversity, full-source analysis, provenance, counterevidence, and unresolved gaps. " +
                "Evaluate measurable criteria and claim support. Concept proposals remain evidence only; bounded Tool Foundry recipes may activate automatically only after deterministic validation and passing tests."
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .callTimeout(4, TimeUnit.MINUTES)
            .retryOnConnectionFailure(retryOnConnectionFailure = true)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(OpenRouterLoggingInterceptor())
                }
            }
            .build()

        private val catalogLock = Mutex()
        private val catalogCache = CatalogCache()
    }
}

private class CatalogCache {
    private var models: List<OpenRouterModel>? = null
    private var timestamp: Long = 0

    fun get(): List<OpenRouterModel>? {
        if (System.currentTimeMillis() - timestamp > 3_600_000L) { // 1 hour TTL
            return null
        }
        return models
    }

    fun getStale(): List<OpenRouterModel>? = models

    fun set(newModels: List<OpenRouterModel>) {
        models = newModels
        timestamp = System.currentTimeMillis()
    }
}

/**
 * Minimal redacting network logger for OpenRouter.
 */
private class OpenRouterLoggingInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = request.url.toString().substringBefore('?')
        val method = request.method
        
        val requestId = UUID.randomUUID().toString().take(8)
        val tag = com.david.openassistant.data.diagnostics.RuntimeDiagnostics.LOGCAT_TAG
        
        android.util.Log.d(tag, "OA_NET level=DEBUG component=network event=request_dispatched request_id=$requestId method=$method url=$url")
        
        val startNs = System.nanoTime()
        val response: okhttp3.Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            android.util.Log.e(tag, "OA_NET level=ERROR component=network event=request_failed request_id=$requestId error=${e.javaClass.simpleName}")
            throw e
        }
        
        val tookMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        android.util.Log.d(tag, "OA_NET level=DEBUG component=network event=response_received request_id=$requestId status=${response.code} duration_ms=$tookMs")
        
        return response
    }
}

internal data class ProviderResponseAttribution(
    val role: AgentTaskRole?,
    val selectionReason: String?,
)

internal data class PreparedOpenRouterRequest(
    val wirePayload: JSONObject,
    val wirePayloadText: String,
    val wirePayloadFingerprint: String,
    val logicalPayloadFingerprint: String,
    val requestContext: ProviderRequestContext,
    val responseAttribution: ProviderResponseAttribution,
    val wireVariantKind: ProviderWireVariantKind,
    val wireVariantOrdinal: Int,
)

internal data class ProviderTransportContext(
    val goalId: String,
    val exchangeId: String,
)
