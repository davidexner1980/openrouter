package com.david.openassistant.domain.tools

import com.david.openassistant.BuildConfig
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.openrouter.requireOpenRouterObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val MAX_SANDBOX_TASK_CHARS = 24_000
private const val MAX_SANDBOX_DATA_CHARS = 96_000
private const val MAX_SANDBOX_OUTPUT_CHARS = 96_000

object HostedSandboxToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "sandbox_workbench",
            displayName = "Hosted sandbox workbench",
            description = "Run code, calculations, tests, parsing, or data analysis inside an isolated OpenRouter-hosted Linux container. It never runs commands on the Android phone. Use it when deterministic local tools are insufficient.",
            parameters = listOf(
                ToolParameter("task", "Precise workbench objective, expected output, and validation requirements."),
                ToolParameter("input_data", "Optional text, code, JSON, CSV, or other data needed for the task. Large payloads remain subject to phone-memory safety limits.", required = false),
                ToolParameter("mode", "Optional mode: auto, code, data, math, document, or test. Defaults to auto.", required = false),
                ToolParameter("allow_web", "Optional true or false. When true, the hosted worker may use OpenRouter web search/fetch. Defaults to false.", required = false),
                ToolParameter("output_format", "Optional text, markdown, or json. Defaults to markdown.", required = false),
            ),
        ),
    )

    fun handles(name: String): Boolean = definitions.any { it.name == name }
}

/**
 * OpenRouter Responses API bridge for powerful computation without granting an
 * LLM shell access to the Android device. Commands run only in an isolated,
 * server-hosted container and the final text is returned as evidence.
 */
class HostedSandboxToolRuntime(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build(),
    private val researchMonitor: ResearchMonitor? = null,
) {
    private val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()

    fun cancelActiveCalls() {
        activeCalls.toList().forEach(okhttp3.Call::cancel)
    }

    suspend fun execute(
        call: OpenRouterToolCall,
        apiKey: String?,
        modelId: String?,
    ): ToolExecutionResult {
        if (!HostedSandboxToolCatalog.handles(call.name)) {
            throw ToolValidationException("Unknown hosted sandbox tool: ${call.name}")
        }
        val key = apiKey?.trim().orEmpty()
        if (key.isBlank()) throw ToolValidationException("The hosted sandbox requires the stored OpenRouter credential.")
        val model = modelId?.trim().orEmpty()
        if (model.isBlank()) throw ToolValidationException("The hosted sandbox requires an OpenRouter model.")
        val args = parseArguments(call.argumentsJson)
        val task = requiredString(args, "task").also {
            if (it.length > MAX_SANDBOX_TASK_CHARS) throw ToolValidationException("Sandbox tasks are limited to $MAX_SANDBOX_TASK_CHARS characters.")
        }
        val inputData = optionalStringAllowEmpty(args, "input_data").orEmpty().also {
            if (it.length > MAX_SANDBOX_DATA_CHARS) throw ToolValidationException("Sandbox input data is limited to $MAX_SANDBOX_DATA_CHARS characters.")
        }
        val mode = optionalString(args, "mode")?.lowercase(Locale.US) ?: "auto"
        if (mode !in setOf("auto", "code", "data", "math", "document", "test")) {
            throw ToolValidationException("Sandbox mode must be auto, code, data, math, document, or test.")
        }
        val requestedWeb = optionalBoolean(args, "allow_web", false)
        val freeOnlyModel = model.equals("openrouter/free", ignoreCase = true) || model.endsWith(":free", ignoreCase = true)
        val allowWeb = requestedWeb && !freeOnlyModel
        val outputFormat = optionalString(args, "output_format")?.lowercase(Locale.US) ?: "markdown"
        if (outputFormat !in setOf("text", "markdown", "json")) {
            throw ToolValidationException("Sandbox output_format must be text, markdown, or json.")
        }

        val payload = JSONObject()
            .put("model", model)
            .put("input", buildPrompt(task, inputData, mode, requestedWeb, allowWeb, outputFormat))
            .put("temperature", 0.0)
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", true)
            .put("tools", buildTools(allowWeb))

        val exchangeId = "openrouter-sandbox-${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val payloadText = payload.toString()
        researchMonitor?.record(
            category = "provider",
            event = "request",
            correlationId = exchangeId,
            fields = mapOf(
                "provider" to "OpenRouter",
                "operation" to "hosted_sandbox_workbench",
                "method" to "POST",
                "endpoint" to RESPONSES_URL,
                "requested_model" to model,
                "safe_headers" to "Accept: application/json; Content-Type: application/json; X-OpenRouter-Title: OpenAssistant Android; X-OpenRouter-Metadata: enabled; User-Agent: OpenAssistant-Android/${BuildConfig.VERSION_NAME}; Authorization: Bearer [REDACTED]",
                "request_body" to payloadText,
                "request_bytes" to payloadText.toByteArray().size,
            ),
        )

        val request = Request.Builder()
            .url(RESPONSES_URL)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-OpenRouter-Title", "OpenAssistant Android")
            .header("X-OpenRouter-Metadata", "enabled")
            .header("User-Agent", "OpenAssistant-Android/${BuildConfig.VERSION_NAME}")
            .post(payloadText.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var responseRecorded = false
        val activeCall = client.newCall(request)
        activeCalls += activeCall
        val root = try {
            activeCall.execute().use { response ->
                val body = response.body.string()
                responseRecorded = true
                researchMonitor?.record(
                    category = "provider",
                    event = "response",
                    level = if (response.isSuccessful) "INFO" else "ERROR",
                    correlationId = exchangeId,
                    fields = mapOf(
                        "provider" to "OpenRouter",
                        "operation" to "hosted_sandbox_workbench",
                        "http_status" to response.code,
                        "successful" to response.isSuccessful,
                        "duration_ms" to (System.currentTimeMillis() - startedAt),
                        "response_headers" to response.headers.toString(),
                        "response_body" to body,
                        "response_bytes" to body.toByteArray().size,
                    ),
                )
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
                        .getOrNull()
                        .orEmpty()
                        .ifBlank { "Hosted sandbox request failed with HTTP ${response.code}." }
                        .replace(key, "[REDACTED]")
                        .take(1_500)
                    throw ToolValidationException(message)
                }
                runCatching { requireOpenRouterObject(body, "Hosted sandbox") }
                    .getOrElse { error ->
                        throw ToolValidationException(
                            error.message.orEmpty().ifBlank { "The hosted sandbox returned unreadable JSON." },
                        )
                    }
            }
        } catch (error: Throwable) {
            if (!responseRecorded) {
                researchMonitor?.record(
                    category = "provider",
                    event = "failure",
                    level = "ERROR",
                    correlationId = exchangeId,
                    fields = mapOf(
                        "provider" to "OpenRouter",
                        "operation" to "hosted_sandbox_workbench",
                        "duration_ms" to (System.currentTimeMillis() - startedAt),
                        "error_type" to error::class.java.name,
                        "error_message" to error.message.orEmpty(),
                    ),
                )
            }
            throw error
        } finally {
            activeCalls -= activeCall
        }

        root.optJSONObject("error")?.let { error ->
            val message = error.optString("message", "The hosted sandbox returned an error.")
                .replace(key, "[REDACTED]")
                .take(1_500)
            throw ToolValidationException(message)
        }
        val outputText = extractOutputText(root).trim()
        if (outputText.isBlank()) {
            throw ToolValidationException("The hosted sandbox completed without a usable final response.")
        }
        val usage = root.optJSONObject("usage")
        val webSearchRequests = maxOf(
            usage?.optJSONObject("server_tool_use")?.optInt("web_search_requests", 0) ?: 0,
            usage?.optJSONObject("server_tool_use_details")?.optInt("web_search_requests", 0) ?: 0,
        )
        val itemTypes = outputItemTypes(root)
        val result = JSONObject()
            .put("status", root.optString("status", "completed"))
            .put("response_id", root.optString("id"))
            .put("resolved_model", root.optString("model", model))
            .put("mode", mode)
            .put("web_enabled", allowWeb)
            .put("output_format", outputFormat)
            .put("output", outputText.take(MAX_SANDBOX_OUTPUT_CHARS))
            .put("output_item_types", JSONArray(itemTypes))
            .put("input_tokens", usage?.optInt("input_tokens", 0) ?: 0)
            .put("output_tokens", usage?.optInt("output_tokens", 0) ?: 0)
            .put("total_tokens", usage?.optInt("total_tokens", 0) ?: 0)
            .put("web_search_requests", webSearchRequests)
        return ToolExecutionResult(
            outputJson = result.toString(),
            displaySummary = "Hosted sandbox completed in $mode mode (${outputText.length} output characters; ${itemTypes.joinToString().ifBlank { "message" }}).",
            promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
            completionTokens = usage?.optInt("output_tokens", 0) ?: 0,
            totalTokens = usage?.optInt("total_tokens", 0) ?: 0,
            costUsd = usage?.optDouble("cost", 0.0) ?: 0.0,
            webSearchRequests = webSearchRequests,
        )
    }

    private fun buildTools(allowWeb: Boolean): JSONArray = JSONArray().apply {
        put(
            JSONObject()
                .put("type", "openrouter:shell")
                .put(
                    "parameters",
                    JSONObject()
                        .put("engine", "openrouter")
                        .put("environment", JSONObject().put("type", "container_auto"))
                        .put("sleep_after_seconds", 60),
                ),
        )
        put(JSONObject().put("type", "openrouter:datetime"))
        if (allowWeb) {
            put(
                JSONObject()
                    .put("type", "openrouter:web_search")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("engine", "auto")
                            .put("search_context_size", "medium"),
                    ),
            )
            put(
                JSONObject()
                    .put("type", "openrouter:web_fetch")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("engine", "auto"),
                    ),
            )
        }
    }

    private fun buildPrompt(
        task: String,
        inputData: String,
        mode: String,
        requestedWeb: Boolean,
        allowWeb: Boolean,
        outputFormat: String,
    ): String = buildString {
        appendLine("You are the isolated computational workbench used by OpenAssistant Android.")
        appendLine("Complete the task, use the hosted shell whenever it materially improves correctness, validate important results, and return the requested deliverable rather than a plan.")
        appendLine("The shell is an ephemeral OpenRouter-hosted Linux sandbox. It is not the user's phone or computer. Do not claim to have changed the user's device, app, or local files.")
        appendLine("Do not request user interaction or permission. Stay within the supplied task and data. Do not expose chain-of-thought; provide concise methods, results, validation, and any material limitations.")
        appendLine("Mode: $mode")
        appendLine("OpenRouter web search/fetch tools available: $allowWeb")
        if (requestedWeb && !allowWeb) {
            appendLine("Paid OpenRouter web tools were disabled because this mission is using a free-only model route.")
        }
        if (allowWeb) {
            appendLine("Use web search or web fetch only when the task materially needs external information, and cite the URLs used in the result.")
        } else {
            appendLine("Do not intentionally access the public network. Do not use curl, wget, package downloads, git clone, remote APIs, sockets, or any equivalent shell command. Work only from the supplied input and software already present in the hosted container.")
            appendLine("This is an instruction boundary; the Android app does not claim that it can independently verify or reconfigure the hosted provider's network policy.")
        }
        appendLine("Required output format: $outputFormat")
        appendLine()
        appendLine("TASK")
        appendLine(task)
        if (inputData.isNotBlank()) {
            appendLine()
            appendLine("INPUT DATA")
            appendLine("<<<OPENASSISTANT_INPUT")
            appendLine(inputData)
            appendLine("OPENASSISTANT_INPUT")
        }
        appendLine()
        appendLine("Before finalizing, test or independently verify calculations/code where practical. Mention only important commands or checks, not hidden reasoning.")
    }.take(MAX_SANDBOX_TASK_CHARS + MAX_SANDBOX_DATA_CHARS + 4_000)

    private fun extractOutputText(root: JSONObject): String {
        val parts = mutableListOf<String>()
        val output = root.optJSONArray("output") ?: return root.optString("output_text")
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val part = content.optJSONObject(contentIndex) ?: continue
                if (part.optString("type") == "output_text") {
                    part.optString("text").takeIf(String::isNotBlank)?.let(parts::add)
                }
            }
        }
        return parts.joinToString("\n\n")
    }

    private fun outputItemTypes(root: JSONObject): List<String> {
        val output = root.optJSONArray("output") ?: return emptyList()
        val types = linkedSetOf<String>()
        for (index in 0 until output.length()) {
            output.optJSONObject(index)?.optString("type")?.takeIf(String::isNotBlank)?.let(types::add)
        }
        return types.toList()
    }

    private fun parseArguments(raw: String): JSONObject = parseToolArguments(raw)

    private fun requiredString(args: JSONObject, name: String): String {
        val value = args.optString(name).trim()
        if (value.isBlank() || value == "null") throw ToolValidationException("Missing required tool argument: $name.")
        return value
    }

    private fun optionalString(args: JSONObject, name: String): String? = args.optString(name)
        .trim()
        .takeIf { it.isNotBlank() && it != "null" }

    private fun optionalStringAllowEmpty(args: JSONObject, name: String): String? = when {
        !args.has(name) || args.isNull(name) -> null
        else -> args.optString(name)
    }

    private fun optionalBoolean(args: JSONObject, name: String, default: Boolean): Boolean {
        val raw = optionalString(args, name) ?: return default
        return raw.toBooleanStrictOrNull() ?: throw ToolValidationException("$name must be true or false.")
    }

    companion object {
        private const val RESPONSES_URL = "https://openrouter.ai/api/v1/responses"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
