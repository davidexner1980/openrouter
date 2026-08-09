package com.david.openassistant.data.openrouter

import com.david.openassistant.agent.ProviderActivityStore
import com.david.openassistant.agent.NonMissionProviderRecord
import com.david.openassistant.agent.ExchangeOutcome
import com.david.openassistant.BuildConfig
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.network.filterSensitive
import com.david.openassistant.domain.tools.OpenRouterToolCall
import com.david.openassistant.domain.tools.SafeToolDefinition
import com.david.openassistant.domain.tools.ToolExecutionResult
import com.david.openassistant.agent.toOpenRouterFunctionTool
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenRouterClient(
    private val client: OkHttpClient = sharedClient,
    private val researchMonitor: ResearchMonitor? = null,
    private val activityStore: ProviderActivityStore? = null,
    private val attachmentDataUrlProvider: (ChatAttachment) -> String = {
        error("No attachment data provider is configured.")
    },
) {
    suspend fun validateKey(apiKey: String): OpenRouterKeyInfo {
        val request = baseRequest(KEY_URL, apiKey).get().build()
        val response = executeCapturedCall(request, "validate_key", null, apiKey = apiKey)
        if (!response.successful) throw openRouterException(response.code, response.body, apiKey)
        return parseOpenRouterKeyInfo(response.body)
    }

    suspend fun fetchModels(apiKey: String): List<OpenRouterModel> {
        val request = baseRequest(MODELS_URL, apiKey).get().build()
        val response = executeCapturedCall(request, "fetch_model_catalog", null, apiKey = apiKey)
        if (!response.successful) throw openRouterException(response.code, response.body, apiKey)
        return parseModels(response.body)
    }

    fun streamChat(
        apiKey: String,
        modelId: String,
        messages: List<ChatMessage>,
        listener: ChatStreamListener,
        freeOnly: Boolean = false,
        toolDefinitions: (() -> JSONArray)? = null,
    ): Call {
        val payload = createBaseChatPayload(modelId, messages).apply {
            put("stream", true)
            toolDefinitions?.let { 
                val tools = it()
                if (tools.length() > 0) {
                    put("tools", tools)
                    put("tool_choice", "auto")
                    put("parallel_tool_calls", true)
                }
            }
        }
        com.david.openassistant.agent.AgentRoutingPolicy.guardPayload(freeOnly, payload)
        return enqueueStream(apiKey, payload, listener)
    }

    suspend fun visionChat(
        apiKey: String,
        modelId: String,
        messages: List<ChatMessage>,
    ): String {
        val payload = createBaseChatPayload(modelId, messages).apply {
            put("stream", false)
        }
        val wirePayloadText = payload.toString()
        val request = baseRequest(CHAT_URL, apiKey)
            .post(wirePayloadText.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val captured = executeCapturedCall(request, "staged_vision_chat", wirePayloadText, apiKey = apiKey)
        if (!captured.successful) throw openRouterException(captured.code, captured.body, apiKey)
        val root = requireOpenRouterObject(captured.body, "OpenRouter vision response")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw OpenRouterException(null, "No response choice from vision model.")
        return choice.optJSONObject("message")?.optString("content").orEmpty()
    }

    /**
     * Runs an automatic function-calling loop until the model returns a final
     * answer or the user explicitly stops it. There is no app-defined round
     * budget. Repeated identical calls are answered from the prior result so a
     * confused model cannot keep producing side effects or waste requests.
     */
    suspend fun runAutomaticToolLoop(
        apiKey: String,
        modelId: String,
        messages: List<ChatMessage>,
        toolDefinitions: () -> JSONArray,
        executeTool: suspend (OpenRouterToolCall) -> ToolExecutionResult,
        shouldStop: () -> Boolean = { false },
        freeOnly: Boolean = false,
        onCallStarted: (Call) -> Unit = {},
        budgetPolicy: com.david.openassistant.agent.ToolBudgetPolicy = com.david.openassistant.agent.ToolBudgetPolicy.CHAT,
    ): AutomaticToolLoopResult {
        val budget = com.david.openassistant.agent.ToolBudget.forPolicy(budgetPolicy)
        val payload = createBaseChatPayload(modelId, messages).apply { put("stream", false) }
        com.david.openassistant.agent.AgentRoutingPolicy.guardPayload(freeOnly, payload)
        val messageArray = payload.getJSONArray("messages")
        val executions = mutableListOf<AutomaticToolExecution>()
        val priorOutputsBySignature = linkedMapOf<String, String>()
        var previousToolCallSignatures: List<String>? = null
        var repeatedNoProgressCycles = 0
        var responseId: String? = null
        var resolvedModel: String? = null
        var finishReason: String? = null
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0
        var totalCost = 0.0
        var round = 0

        val loopStartedAt = System.currentTimeMillis()
        val deadline = loopStartedAt + budget.maxDurationMs
        val availableNames = mutableSetOf<String>()
        
        var currentCall: Call? = null
        
        while (round < budget.maxRounds) {
            val now = System.currentTimeMillis()
            if (shouldStop() || now > deadline) {
                currentCall?.cancel()
                throw OpenRouterException(null, if (now > deadline) "Tool execution loop exceeded the maximum duration of ${budget.maxDurationMs / 1000} seconds." else "Generation was stopped.")
            }
            if (executions.size >= budget.maxExecutions) {
                throw OpenRouterException(null, "Tool execution loop exceeded the maximum of ${budget.maxExecutions} tool calls.")
            }

            val toolArray = toolDefinitions()
            if (toolArray.length() == 0) throw OpenRouterException(null, "No autonomous tools are available.")
            
            availableNames.clear()
            for (i in 0 until toolArray.length()) {
                val toolObj = toolArray.optJSONObject(i) ?: continue
                val type = toolObj.optString("type")
                if (type == "function") {
                    toolObj.optJSONObject("function")?.optString("name")?.let { availableNames.add(it) }
                } else if (type.startsWith("openrouter:")) {
                    availableNames.add(type)
                }
            }

            payload.put("tool_choice", "auto")
            payload.put("parallel_tool_calls", true)
            payload.put("tools", toolArray)
            
            OpenRouterProtocolUtils.validateOutboundRequest(payload)

            val wirePayloadText = payload.toString()
            val request = baseRequest(CHAT_URL, apiKey)
                .post(wirePayloadText.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val call = client.newCall(request)
            currentCall = call
            onCallStarted(call)
            val captured = executeCapturedCall(
                request = request,
                operation = "automatic_tool_loop_round_$round",
                requestBody = wirePayloadText,
                prebuiltCall = call,
                apiKey = apiKey,
            )
            if (!captured.successful) throw openRouterException(captured.code, captured.body, apiKey)
            val root = requireOpenRouterObject(captured.body, "OpenRouter automatic tool response")
            root.optJSONObject("error")?.let { error ->
                throw OpenRouterException(
                    error.optInt("code").takeIf { it > 0 },
                    SecretRedactor.redact(error.optString("message", "OpenRouter returned an error."), apiKey),
                )
            }
            responseId = root.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: responseId
            resolvedModel = root.optString("model").takeIf { it.isNotBlank() && it != "null" } ?: resolvedModel
            root.optJSONObject("usage")?.let { usage ->
                promptTokens += usage.optIntOrNull("prompt_tokens") ?: usage.optIntOrNull("input_tokens") ?: 0
                completionTokens += usage.optIntOrNull("completion_tokens") ?: usage.optIntOrNull("output_tokens") ?: 0
                totalTokens += usage.optIntOrNull("total_tokens") ?: 0
                totalCost += usage.optDoubleOrNull("cost") ?: 0.0
            }
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: throw OpenRouterException(null, "The selected model returned no response choice.")
            val choiceError = choice.optJSONObject("error")
            if (choiceError != null || choice.optString("finish_reason") == "error") {
                val code = choiceError?.optInt("code")?.takeIf { it > 0 } ?: 429
                val message = choiceError?.optString("message")
                    ?: "The selected model returned a choice-level error."
                throw OpenRouterException(
                    statusCode = code,
                    userMessage = SecretRedactor.redact(message, apiKey),
                )
            }
            finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" } ?: finishReason
            val message = choice.optJSONObject("message")
                ?: throw OpenRouterException(null, "The selected model returned an invalid response message.")
            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                val currentToolCallSignatures = buildList {
                    for (index in 0 until calls.length()) {
                        val rawCall = calls.optJSONObject(index) ?: continue
                        val function = rawCall.optJSONObject("function") ?: continue
                        val name = function.optString("name")
                        val args = function.optString("arguments")
                        
                        val normalizedArgs = runCatching {
                            val json = JSONObject(args)
                            OpenRouterProtocolUtils.toSortedString(json)
                        }.getOrDefault(args.trim())
                        
                        add("$name:$normalizedArgs")
                    }
                }
                repeatedNoProgressCycles = if (currentToolCallSignatures == previousToolCallSignatures) {
                    repeatedNoProgressCycles + 1
                } else {
                    0
                }
                previousToolCallSignatures = currentToolCallSignatures
                if (repeatedNoProgressCycles >= 2) {
                    throw OpenRouterException(
                        null,
                        "The selected model repeated the same tool requests without making progress. Retry with another model or a more specific request.",
                    )
                }
                messageArray.put(JSONObject(message.toString()).put("role", "assistant"))
                for (index in 0 until calls.length()) {
                    if (executions.size >= budget.maxExecutions) {
                        throw OpenRouterException(null, "Tool execution loop exceeded the maximum of ${budget.maxExecutions} tool calls.")
                    }
                    if (System.currentTimeMillis() > deadline) {
                        throw OpenRouterException(null, "Tool execution loop exceeded the maximum duration.")
                    }

                    val rawCall = calls.optJSONObject(index)
                        ?: throw OpenRouterException(null, "The selected model returned an invalid tool request.")
                    val function = rawCall.optJSONObject("function")
                        ?: throw OpenRouterException(null, "The selected model returned a tool request without a function.")
                    val toolCall = OpenRouterToolCall(
                        id = rawCall.optString("id").ifBlank { "automatic_tool_${round}_$index" },
                        name = function.optString("name"),
                        argumentsJson = function.optString("arguments").ifBlank { "{}" },
                    )
                    
                    val normalizedArgsForSignature = runCatching {
                        val json = JSONObject(toolCall.argumentsJson)
                        OpenRouterProtocolUtils.toSortedString(json)
                    }.getOrDefault(toolCall.argumentsJson.trim())
                    
                    val signature = "${toolCall.name}:$normalizedArgsForSignature"
                    
                    val output = priorOutputsBySignature[signature]?.let { prior ->
                        val summary = "Reused the prior result for an identical tool request."
                        executions += AutomaticToolExecution(toolCall.name, summary, succeeded = true)
                        JSONObject()
                            .put("status", "ok")
                            .put("reused", true)
                            .put("result", prior)
                            .toString()
                    } ?: if (toolCall.name !in availableNames) {
                        val error = "The requested local tool is not registered: ${toolCall.name}."
                        executions += AutomaticToolExecution(toolCall.name, error, false)
                        JSONObject().put("status", "error").put("error", error).toString()
                    } else {
                        runCatching { executeTool(toolCall) }.fold(
                            onSuccess = { result ->
                                promptTokens += result.promptTokens
                                completionTokens += result.completionTokens
                                totalTokens += result.totalTokens
                                totalCost += result.costUsd
                                executions += AutomaticToolExecution(toolCall.name, result.displaySummary.take(600), succeeded = true)
                                result.outputJson.take(budget.maxOutputChars).also {
                                    priorOutputsBySignature[signature] = it
                                }
                            }
                        ) { error ->
                            val messageText = error.message.orEmpty().ifBlank { "Local tool execution failed." }.take(1_000)
                            executions += AutomaticToolExecution(toolCall.name, messageText, succeeded = false)
                            JSONObject().put("status", "error").put("tool_name", toolCall.name).put("error", messageText).toString()
                        }
                    }
                    messageArray.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", toolCall.id)
                            .put("name", toolCall.name)
                            .put("content", output),
                    )
                }
                payload.put("messages", messageArray)
                round += 1
                continue
            }

            val content = message.optString("content").takeIf { it.isNotBlank() && it != "null" }
                ?: throw OpenRouterException(null, "The selected model returned neither text nor a tool request.")
            return AutomaticToolLoopResult(
                content = content,
                summary = StreamSummary(
                    responseId = responseId,
                    resolvedModel = resolvedModel,
                    finishReason = finishReason,
                    promptTokens = promptTokens.takeIf { it > 0 },
                    completionTokens = completionTokens.takeIf { it > 0 },
                    totalTokens = totalTokens.takeIf { it > 0 },
                    cost = totalCost.takeIf { it > 0.0 },
                ),
                executions = executions.toList(),
            )
        }
        throw OpenRouterException(null, "Tool execution loop exceeded the maximum of ${budget.maxRounds} rounds.")
    }

    private fun enqueueStream(
        apiKey: String,
        payload: JSONObject,
        listener: ChatStreamListener,
    ): Call {
        OpenRouterProtocolUtils.validateOutboundRequest(payload)
        val exchangeId = "openrouter-${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        val wirePayloadText = payload.toString()
        val safeDiagnosticPayloadText = OpenRouterProtocolUtils.sanitizeForDiagnostics(wirePayloadText)
        researchMonitor?.record(
            category = "provider",
            event = "request",
            correlationId = exchangeId,
            fields = mapOf(
                "provider" to "OpenRouter",
                "operation" to "stream_chat_completion",
                "method" to "POST",
                "endpoint" to CHAT_URL,
                "requested_model" to payload.optString("model"),
                "safe_headers" to "Accept: text/event-stream; Content-Type: application/json; X-OpenRouter-Title: OpenAssistant Android; User-Agent: OpenAssistant-Android/${BuildConfig.VERSION_NAME}; Authorization: Bearer [REDACTED]",
                "request_body" to safeDiagnosticPayloadText,
                "request_bytes" to wirePayloadText.toByteArray().size,
            ),
        )
        val request = baseRequest(CHAT_URL, apiKey)
            .header("Accept", "text/event-stream")
            .post(wirePayloadText.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val duration = System.currentTimeMillis() - startedAt
                    researchMonitor?.record(
                        category = "provider",
                        event = "failure",
                        level = "ERROR",
                        correlationId = exchangeId,
                        fields = mapOf(
                            "provider" to "OpenRouter",
                            "operation" to "stream_chat_completion",
                            "cancelled" to call.isCanceled(),
                            "duration_ms" to duration,
                            "error_type" to e::class.java.name,
                            "error_message" to e.message.orEmpty(),
                        ),
                    )
                    activityStore?.recordActivity(
                        NonMissionProviderRecord(
                            exchangeId = exchangeId,
                            contextType = "CONVERSATION",
                            contextId = "stream",
                            operation = "stream_chat",
                            requestedModel = payload.optString("model"),
                            outcome = if (call.isCanceled()) ExchangeOutcome.CANCELLED else ExchangeOutcome.TRANSPORT_FAILURE,
                            startedAt = startedAt,
                            finishedAt = System.currentTimeMillis(),
                            failureClass = if (call.isCanceled()) "CANCELLED" else e::class.java.simpleName
                        )
                    )
                    listener.onError(
                        OpenRouterException(
                            statusCode = null,
                            userMessage = if (call.isCanceled()) {
                                "Generation was stopped."
                            } else {
                                e.message.orEmpty().ifBlank { "Network request failed." }
                            },
                        ),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { currentResponse ->
                        val duration = System.currentTimeMillis() - startedAt
                        if (!currentResponse.isSuccessful) {
                            val body = currentResponse.body.string()
                            researchMonitor?.record(
                                category = "provider",
                                event = "response",
                                level = "ERROR",
                                correlationId = exchangeId,
                                fields = mapOf(
                                    "provider" to "OpenRouter",
                                    "operation" to "stream_chat_completion",
                                    "http_status" to currentResponse.code,
                                    "successful" to false,
                                    "duration_ms" to duration,
                                    "response_headers" to currentResponse.headers.filterSensitive().toString(),
                                    "response_body" to SecretRedactor.redact(body, apiKey),
                                    "response_bytes" to body.toByteArray().size,
                                ),
                            )
                            activityStore?.recordActivity(
                                NonMissionProviderRecord(
                                    exchangeId = exchangeId,
                                    contextType = "CONVERSATION",
                                    contextId = "stream",
                                    operation = "stream_chat",
                                    requestedModel = payload.optString("model"),
                                    outcome = if (currentResponse.code == 429) ExchangeOutcome.RATE_LIMITED else ExchangeOutcome.RESPONSE_ERROR,
                                    startedAt = startedAt,
                                    finishedAt = System.currentTimeMillis(),
                                    failureClass = "HTTP_${currentResponse.code}"
                                )
                            )
                            listener.onError(currentResponse.toException(body, apiKey))
                            return@use
                        }

                        val mutableSummary = MutableStreamSummary()
                        val source = currentResponse.body.source()
                        val rawStream = StringBuilder()
                        var responseRecorded = false

                        fun appendRawLine(line: String) {
                            if (rawStream.length >= MAX_CAPTURED_STREAM_CHARS) return
                            val remaining = MAX_CAPTURED_STREAM_CHARS - rawStream.length
                            rawStream.append(line.take(remaining))
                            if (rawStream.length < MAX_CAPTURED_STREAM_CHARS) rawStream.append('\n')
                        }

                        fun recordStreamResponse(level: String, error: Throwable? = null) {
                            if (responseRecorded) return
                            responseRecorded = true
                            val safeHeaders = currentResponse.headers.filterSensitive()
                            val finalOutcome = when {
                                error != null -> ExchangeOutcome.RESPONSE_ERROR
                                call.isCanceled() -> ExchangeOutcome.CANCELLED
                                else -> ExchangeOutcome.RESPONSE_SUCCESS
                            }
                            val summary = mutableSummary.toImmutable()
                            researchMonitor?.record(
                                category = "provider",
                                event = "response",
                                level = level,
                                correlationId = exchangeId,
                                fields = mapOf(
                                    "provider" to "OpenRouter",
                                    "operation" to "stream_chat_completion",
                                    "http_status" to currentResponse.code,
                                    "successful" to (level == "INFO"),
                                    "cancelled" to call.isCanceled(),
                                    "duration_ms" to (System.currentTimeMillis() - startedAt),
                                    "response_headers" to safeHeaders.toString(),
                                    "response_body_sse" to SecretRedactor.redact(rawStream.toString(), apiKey),
                                    "stream_capture_truncated" to (rawStream.length >= MAX_CAPTURED_STREAM_CHARS),
                                    "error_type" to error?.let { it::class.java.name },
                                    "error_message" to error?.message,
                                ),
                            )
                            activityStore?.recordActivity(
                                NonMissionProviderRecord(
                                    exchangeId = exchangeId,
                                    contextType = "CONVERSATION",
                                    contextId = "stream",
                                    operation = "stream_chat",
                                    requestedModel = payload.optString("model"),
                                    outcome = finalOutcome,
                                    promptTokens = summary.promptTokens,
                                    completionTokens = summary.completionTokens,
                                    totalTokens = summary.totalTokens,
                                    costUsdMicros = summary.cost?.let { (it * 1_000_000).toLong() },
                                    startedAt = startedAt,
                                    finishedAt = System.currentTimeMillis(),
                                    failureClass = error?.javaClass?.simpleName
                                )
                            )
                        }

                        try {
                            streamLoop@ while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                appendRawLine(line)
                                when (val event = SseEventParser.parse(line)) {
                                    SseEvent.Ignore -> Unit
                                    SseEvent.Done -> break@streamLoop
                                    is SseEvent.Data -> {
                                        val shouldContinue = parseStreamPayload(
                                            payload = event.payload,
                                            summary = mutableSummary,
                                            listener = listener,
                                            apiKey = apiKey,
                                        )
                                        if (!shouldContinue) {
                                            recordStreamResponse("ERROR")
                                            return@use
                                        }
                                    }
                                }
                            }
                            recordStreamResponse(if (call.isCanceled()) "WARN" else "INFO")
                            if (!call.isCanceled()) {
                                listener.onComplete(mutableSummary.toImmutable())
                            }
                        } catch (exception: Exception) {
                            recordStreamResponse("ERROR", exception)
                            if (!call.isCanceled()) {
                                listener.onError(
                                    OpenRouterException(
                                        statusCode = null,
                                        userMessage = SecretRedactor.redact(
                                            exception.message.orEmpty().ifBlank {
                                                "The response stream could not be read."
                                            },
                                            apiKey,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        )
        return call
    }

    private fun createBaseChatPayload(
        modelId: String,
        messages: List<ChatMessage>,
    ): JSONObject = JSONObject().apply {
        put("model", modelId)
        put(
            "messages",
            JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", SYSTEM_PROMPT),
                )
                messages.forEach { message ->
                    val content = if (message.attachments.isEmpty()) {
                        message.content
                    } else {
                        JSONArray().apply {
                            message.toMultimodalContentPlan().forEach { part ->
                                when (part) {
                                    is ChatContentPartPlan.Text -> put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", part.text),
                                    )

                                    is ChatContentPartPlan.Image -> put(
                                        JSONObject()
                                            .put("type", "image_url")
                                            .put(
                                                "image_url",
                                                JSONObject().put(
                                                    "url",
                                                    attachmentDataUrlProvider(part.attachment),
                                                ),
                                            ),
                                    )

                                    is ChatContentPartPlan.Pdf -> put(
                                        JSONObject()
                                            .put("type", "text")
                                            .put("text", "[PDF Document: ${part.attachment.displayName}]"),
                                    )
                                }
                            }
                        }
                    }
                    put(
                        JSONObject()
                            .put("role", message.role.wireName)
                            .put("content", content),
                    )
                }
            },
        )
    }

    private fun parseStreamPayload(
        payload: String,
        summary: MutableStreamSummary,
        listener: ChatStreamListener,
        apiKey: String,
    ): Boolean {
        val json = requireOpenRouterObject(payload, "OpenRouter stream event")
        val rootError = json.optJSONObject("error")
        if (rootError != null) {
            listener.onError(
                OpenRouterException(
                    statusCode = rootError.optInt("code").takeIf { it > 0 },
                    userMessage = SecretRedactor.redact(
                        rootError.optString("message", "OpenRouter returned an error."),
                        apiKey,
                    ),
                ),
            )
            return false
        }

        json.optString("id").takeIf { it.isNotBlank() }?.let { summary.responseId = it }
        json.optString("model").takeIf { it.isNotBlank() }?.let { summary.resolvedModel = it }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.optJSONObject(0)
            val choiceError = choice?.optJSONObject("error")
            if (choiceError != null) {
                listener.onError(
                    OpenRouterException(
                        statusCode = choiceError.optInt("code").takeIf { it > 0 },
                        userMessage = SecretRedactor.redact(
                            choiceError.optString("message", "The selected model returned an error."),
                            apiKey,
                        ),
                    ),
                )
                return false
            }

            choice?.optString("finish_reason")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { summary.finishReason = it }

            val deltaText = choice
                ?.optJSONObject("delta")
                ?.optString("content")
                .orEmpty()
            if (deltaText.isNotEmpty()) listener.onDelta(deltaText)
        }

        json.optJSONObject("usage")?.let { usage ->
            summary.promptTokens = usage.optIntOrNull("prompt_tokens")
            summary.completionTokens = usage.optIntOrNull("completion_tokens")
            summary.totalTokens = usage.optIntOrNull("total_tokens")
            summary.cost = usage.optDoubleOrNull("cost")
        }
        return true
    }


    private fun executeCapturedCall(
        request: Request,
        operation: String,
        requestBody: String?,
        prebuiltCall: Call? = null,
        apiKey: String? = null,
    ): CapturedHttpResponse {
        val exchangeId = "openrouter-${UUID.randomUUID()}"
        val startedAt = System.currentTimeMillis()
        
        // Use a decoupled copy for diagnostics immediately if a body exists.
        val safeDiagnosticRequestBody = requestBody?.let { 
             com.david.openassistant.data.diagnostics.redactResearchMonitorText(it) 
        }

        researchMonitor?.record(
            category = "provider",
            event = "request",
            correlationId = exchangeId,
            fields = mapOf(
                "provider" to "OpenRouter",
                "operation" to operation,
                "method" to request.method,
                "endpoint" to request.url.toString(),
                "safe_headers" to "Accept: ${request.header("Accept").orEmpty()}; Content-Type: ${request.header("Content-Type").orEmpty()}; X-OpenRouter-Title: OpenAssistant Android; User-Agent: OpenAssistant-Android/${BuildConfig.VERSION_NAME}; Authorization: Bearer [REDACTED]",
                "request_body" to safeDiagnosticRequestBody,
                "request_bytes" to requestBody?.toByteArray()?.size,
            ),
        )
        var responseRecorded = false
        try {
            return (prebuiltCall ?: client.newCall(request)).execute().use { response ->
                val body = response.body.string()
                responseRecorded = true
                val semanticSuccess = response.isSuccessful && !hasEmbeddedChoiceError(body)
                researchMonitor?.record(
                    category = "provider",
                    event = "response",
                    level = if (semanticSuccess) "INFO" else "ERROR",
                    correlationId = exchangeId,
                    fields = mapOf(
                        "provider" to "OpenRouter",
                        "operation" to operation,
                        "http_status" to response.code,
                        "successful" to semanticSuccess,
                        "duration_ms" to (System.currentTimeMillis() - startedAt),
                        "response_headers" to response.headers.filterSensitive().toString(),
                        "response_body" to SecretRedactor.redact(body, apiKey),
                        "response_bytes" to body.toByteArray().size,
                    ),
                )
                activityStore?.recordActivity(
                    NonMissionProviderRecord(
                        exchangeId = exchangeId,
                        contextType = "INFRASTRUCTURE",
                        contextId = operation,
                        operation = operation,
                        requestedModel = "meta",
                        outcome = when {
                            semanticSuccess -> ExchangeOutcome.RESPONSE_SUCCESS
                            response.code == 429 -> ExchangeOutcome.RATE_LIMITED
                            response.code == 401 || response.code == 403 -> ExchangeOutcome.AUTHENTICATION_FAILED
                            else -> ExchangeOutcome.RESPONSE_ERROR
                        },
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        failureClass = if (!semanticSuccess) "HTTP_${response.code}" else null
                    )
                )
                CapturedHttpResponse(response.code, semanticSuccess, body)
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
                        "operation" to operation,
                        "duration_ms" to (System.currentTimeMillis() - startedAt),
                        "error_type" to error::class.java.name,
                        "error_message" to error.message.orEmpty(),
                    ),
                )
            }
            throw error
        }
    }

    private fun baseRequest(url: String, apiKey: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("X-OpenRouter-Title", "OpenAssistant Android")
            .header("User-Agent", "OpenAssistant-Android/${BuildConfig.VERSION_NAME}")

    private fun hasEmbeddedChoiceError(body: String): Boolean = runCatching {
        val tokener = JSONTokener(body)
        val json = tokener.nextValue() as? JSONObject
        val choices = json?.optJSONArray("choices")
        val choice = choices?.optJSONObject(0)
        (choice?.has("error") == true) || (choice?.optString("finish_reason") == "error")
    }.getOrDefault(false)

    private fun validateOutboundRequest(payload: JSONObject) {
        val model = payload.optString("model")
        if (model.isBlank()) return // Some non-chat requests might omit model

        // conversational check is less strict on models but still checks for reasoning shape
        if (payload.has("reasoning")) {
            val reasoning = payload.opt("reasoning")
            require(reasoning is JSONObject) { "The 'reasoning' property must be a JSON object, not a string." }
        }
        
        val payloadStr = payload.toString()
        require(!payloadStr.contains("[REDACTED]") && !payloadStr.contains("[EXCLUDED]")) {
            "Outbound request contains a diagnostic redaction marker."
        }
        require(!payloadStr.contains("sk-or-") && !payloadStr.contains("Bearer ")) {
            "Outbound request contains raw credentials."
        }
    }

    private fun parseModels(body: String): List<OpenRouterModel> {
        val data = requireOpenRouterObject(body, "OpenRouter model catalog").optJSONArray("data") ?: JSONArray()
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

    private fun Response.toException(body: String, apiKey: String): OpenRouterException {
        return openRouterException(code, body, apiKey)
    }

    private fun openRouterException(statusCode: Int, body: String, apiKey: String): OpenRouterException {
        val parsedMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return OpenRouterException(
            statusCode = statusCode,
            userMessage = SecretRedactor.redact(
                parsedMessage.orEmpty().ifBlank { "OpenRouter request failed with HTTP $statusCode." },
                apiKey,
            ),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private fun String?.toNullableDouble(): Double? =
        this?.takeIf { (it.isNotBlank()) && (it != "null") }?.toDoubleOrNull()

    private data class MutableStreamSummary(
        var responseId: String? = null,
        var resolvedModel: String? = null,
        var finishReason: String? = null,
        var promptTokens: Int? = null,
        var completionTokens: Int? = null,
        var totalTokens: Int? = null,
        var cost: Double? = null,
    ) {
        fun toImmutable() = StreamSummary(
            responseId = responseId,
            resolvedModel = resolvedModel,
            finishReason = finishReason,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cost = cost,
        )
    }

    private data class CapturedHttpResponse(
        val code: Int,
        val successful: Boolean,
        val body: String,
    )

    internal companion object {
        const val KEY_URL = "https://openrouter.ai/api/v1/key"
        const val MODELS_URL = "https://openrouter.ai/api/v1/models"
        const val CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val SYSTEM_PROMPT =
            "You are OpenAssistant, a careful Android AI assistant. Be truthful, practical, and explicit about uncertainty. " +
                "Never claim that an action occurred unless the app provides a tool result proving it. " +
                "When tools are available, select and call as many bounded local tools as materially improve correctness. " +
                "Low-risk local tools run automatically; returned tool results are authoritative evidence of what ran. " +
                "When an image is attached, inspect only what is actually visible and state uncertainty when details are unclear."
        const val MAX_CAPTURED_STREAM_CHARS = 4_000_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .callTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(retryOnConnectionFailure = true)
            .build()
    }
}

interface ChatStreamListener {
    fun onDelta(text: String)
    fun onComplete(summary: StreamSummary)
    fun onError(error: OpenRouterException)
}

enum class OpenRouterFailureClass {
    LOCAL_REQUEST_SCHEMA_FAILURE,
    NETWORK_DNS_FAILURE,
    NETWORK_OFFLINE,
    PROVIDER_RATE_LIMIT,
    PROVIDER_CAPACITY,
    PROVIDER_AUTHENTICATION,
    PROVIDER_MODEL_INCOMPATIBILITY,
    RESPONSE_SCHEMA_FAILURE,
    EMPTY_MODEL_OUTPUT,
    TOOL_ARGUMENT_FAILURE,
    SEARCH_PROVIDER_FAILURE,
    SOURCE_ACCESS_FAILURE,
    NO_PROGRESS,
    RECONCILIATION_CONFLICT,
    APPLICATION_INVARIANT_FAILURE,
}

class OpenRouterException(
    val statusCode: Int?,
    val userMessage: String,
    cause: Throwable? = null,
    val role: com.david.openassistant.agent.AgentTaskRole? = null,
    val selectionReason: String? = null,
    val previousRoute: String? = null,
    val cooldownState: String? = null,
    val provider: String? = null,
    val finishReason: String? = null,
    val nativeFinishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsd: Double? = null,
    val webSearchRequests: Int? = null,
    val failureClass: OpenRouterFailureClass? = null,
    val fieldPath: String? = null,
    val validationReason: String? = null,
    val originalPayloadFingerprint: String? = null,
    val repairedPayloadFingerprint: String? = null,
    val repairApplied: String? = null,
    val repairRetryCount: Int? = null,
) : IOException(userMessage, cause)
