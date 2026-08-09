package com.david.openassistant.data.openrouter

import com.david.openassistant.data.diagnostics.redactResearchMonitorText
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Shared canonical protocol utilities for OpenRouter requests, responses,
 * validation, sanitization, structural marker validation, reasoning repair,
 * and payload fingerprinting.
 */
object OpenRouterProtocolUtils {

    const val AUTO_BETA_ROUTER_MODEL_ID = "openrouter/auto-beta"
    const val FREE_ROUTER_MODEL_ID = "openrouter/free"
    const val BODY_BUILDER_MODEL_ID = "openrouter/bodybuilder"

    private val ALLOWED_ROUTER_MODELS = setOf(
        AUTO_BETA_ROUTER_MODEL_ID,
        FREE_ROUTER_MODEL_ID,
        BODY_BUILDER_MODEL_ID,
    )

    private val CORE_SERVER_TOOL_TYPES = setOf(
        "openrouter:web_search",
        "openrouter:web_fetch",
        "openrouter:datetime",
        "openrouter:shell",
    )

    /**
     * Calculates a stable SHA-256 fingerprint for a payload JSONObject string representation.
     * Uses a deterministic sorted-key serialization to ensure stability across retries and restarts.
     */
    fun computePayloadFingerprint(payload: JSONObject): String {
        return computePayloadFingerprint(toSortedString(payload))
    }

    fun computePayloadFingerprint(payloadText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(payloadText.toByteArray(StandardCharsets.UTF_8))
        return "sha256:v1:${hash.joinToString("") { "%02x".format(it) }}"
    }



    private fun toSortedString(obj: JSONObject): String {
        val keys = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        
        return buildString {
            append("{")
            keys.forEachIndexed { index, key ->
                if (index > 0) append(",")
                append(JSONObject.quote(key))
                append(":")
                append(valueToString(obj.get(key)))
            }
            append("}")
        }
    }

    private fun valueToString(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> toSortedString(value)
            is JSONArray -> {
                buildString {
                    append("[")
                    for (i in 0 until value.length()) {
                        if (i > 0) append(",")
                        append(valueToString(value.get(i)))
                    }
                    append("]")
                }
            }
            is String -> JSONObject.quote(value)
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString()
        }
    }


    /**
     * Validates an outbound canonical OpenRouter request payload.
     * Throws an [OpenRouterException] with structured [LocalSchemaFailureDetails] if invalid.
     */
    fun validateOutboundRequest(payload: JSONObject) {
        val payloadFingerprint = computePayloadFingerprint(payload)
        val model = payload.optString("model")
        if (model.isBlank()) {
            throw OpenRouterException(
                statusCode = null,
                userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Outbound request is missing 'model'.",
                failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                fieldPath = "model",
                validationReason = "Outbound request is missing 'model'.",
                originalPayloadFingerprint = payloadFingerprint,
            )
        }

        if (model !in ALLOWED_ROUTER_MODELS && !model.endsWith(":free")) {
            throw OpenRouterException(
                statusCode = null,
                userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Unauthorized model for autonomous request: $model. Must use auto-beta, free, or bodybuilder routers.",
                failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                fieldPath = "model",
                validationReason = "Unauthorized model for autonomous request: $model.",
                originalPayloadFingerprint = payloadFingerprint,
            )
        }

        payload.optJSONArray("models")?.let { models ->
            for (i in 0 until models.length()) {
                val m = models.optString(i)
                if (m !in ALLOWED_ROUTER_MODELS && !m.endsWith(":free")) {
                    throw OpenRouterException(
                        statusCode = null,
                        userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Unauthorized fallback model: $m",
                        failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                        fieldPath = "models[$i]",
                        validationReason = "Unauthorized fallback model: $m",
                        originalPayloadFingerprint = payloadFingerprint,
                    )
                }
                if (m == BODY_BUILDER_MODEL_ID) {
                    throw OpenRouterException(
                        statusCode = null,
                        userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Body Builder must never appear in a fallback models array.",
                        failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                        fieldPath = "models[$i]",
                        validationReason = "Body Builder must never appear in a fallback models array.",
                        originalPayloadFingerprint = payloadFingerprint,
                    )
                }
            }
        }

        val messages = payload.optJSONArray("messages")
        if (messages == null || messages.length() == 0) {
            throw OpenRouterException(
                statusCode = null,
                userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Outbound request is missing 'messages'.",
                failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                fieldPath = "messages",
                validationReason = "Outbound request is missing 'messages'.",
                originalPayloadFingerprint = payloadFingerprint,
            )
        }

        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            if (role !in setOf("system", "user", "assistant", "tool")) {
                throw OpenRouterException(
                    statusCode = null,
                    userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Invalid message role: $role at index $i",
                    failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                    fieldPath = "messages[$i].role",
                    validationReason = "Invalid message role: $role",
                    originalPayloadFingerprint = payloadFingerprint,
                )
            }

            val content = msg.opt("content")
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    val type = part.optString("type")
                    if (type !in setOf("text", "image_url")) {
                        throw OpenRouterException(
                            statusCode = null,
                            userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Invalid content part type: $type at message $i part $j",
                            failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                            fieldPath = "messages[$i].content[$j].type",
                            validationReason = "Invalid content part type: $type",
                            originalPayloadFingerprint = payloadFingerprint,
                        )
                    }
                    if (type == "image_url") {
                        val urlObj = part.optJSONObject("image_url")
                        if (urlObj == null || !urlObj.has("url")) {
                            throw OpenRouterException(
                                statusCode = null,
                                userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Missing image URL in message $i part $j",
                                failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                                fieldPath = "messages[$i].content[$j].image_url",
                                validationReason = "Missing image URL",
                                originalPayloadFingerprint = payloadFingerprint,
                            )
                        }
                    }
                }
            } else if (content != null && content != JSONObject.NULL) {
                if (content !is String) {
                    throw OpenRouterException(
                        statusCode = null,
                        userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Message content must be string or array at message $i.",
                        failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                        fieldPath = "messages[$i].content",
                        validationReason = "Message content must be string or array.",
                        originalPayloadFingerprint = payloadFingerprint,
                    )
                }
            }
        }

        // 3. Reasoning must be a JSON object for OpenRouter to avoid HTTP 400.
        if (payload.has("reasoning")) {
            val reasoning = payload.opt("reasoning")
            if (reasoning !is JSONObject) {
                throw OpenRouterException(
                    statusCode = null,
                    userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): The top-level 'reasoning' property must be a JSON object, not a string.",
                    failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                    fieldPath = "reasoning",
                    validationReason = "The top-level 'reasoning' property must be a JSON object, not a string.",
                    originalPayloadFingerprint = payloadFingerprint,
                )
            }
            if (!reasoning.has("effort")) {
                throw OpenRouterException(
                    statusCode = null,
                    userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): The 'reasoning' object is missing the required 'effort' field.",
                    failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                    fieldPath = "reasoning.effort",
                    validationReason = "The 'reasoning' object is missing the required 'effort' field.",
                    originalPayloadFingerprint = payloadFingerprint,
                )
            }
        }

        // 4. Tools and response format
        payload.optJSONArray("tools")?.let { tools ->
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val type = tool.optString("type")
                if (type.startsWith("openrouter:")) {
                    if (type !in CORE_SERVER_TOOL_TYPES && type !in setOf("openrouter:subagent", "openrouter:advisor", "openrouter:fusion")) {
                        throw OpenRouterException(
                            statusCode = null,
                            userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Unsupported OpenRouter server tool: $type",
                            failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                            fieldPath = "tools[$i].type",
                            validationReason = "Unsupported OpenRouter server tool: $type",
                            originalPayloadFingerprint = payloadFingerprint,
                        )
                    }
                    val parameters = tool.optJSONObject("parameters")
                    if (parameters != null && parameters.has("reasoning")) {
                        if (parameters.opt("reasoning") !is JSONObject) {
                            throw OpenRouterException(
                                statusCode = null,
                                userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Server tool reasoning parameters must be an object.",
                                failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                                fieldPath = "tools[$i].parameters.reasoning",
                                validationReason = "Server tool reasoning parameters must be an object.",
                                originalPayloadFingerprint = payloadFingerprint,
                            )
                        }
                    }
                } else {
                    if (type != "function") {
                        throw OpenRouterException(
                            statusCode = null,
                            userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Unsupported tool type: $type",
                            failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                            fieldPath = "tools[$i].type",
                            validationReason = "Unsupported tool type: $type",
                            originalPayloadFingerprint = payloadFingerprint,
                        )
                    }
                }
            }
        }

        payload.optJSONObject("response_format")?.let { fmt ->
            val type = fmt.optString("type")
            if (type !in setOf("text", "json_object", "json_schema")) {
                throw OpenRouterException(
                    statusCode = null,
                    userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Invalid response_format type: $type",
                    failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                    fieldPath = "response_format.type",
                    validationReason = "Invalid response_format type: $type",
                    originalPayloadFingerprint = payloadFingerprint,
                )
            }
            if (type == "json_schema" && !fmt.has("json_schema")) {
                throw OpenRouterException(
                    statusCode = null,
                    userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Missing 'json_schema' object in response_format.",
                    failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                    fieldPath = "response_format.json_schema",
                    validationReason = "Missing 'json_schema' object in response_format.",
                    originalPayloadFingerprint = payloadFingerprint,
                )
            }
        }

        // 5. Narrowly scoped structural marker check: Check only application-generated structural fields.
        val structuralKeys = setOf("reasoning", "reasoning_details", "response_format", "tools", "models", "plugins")
        for (key in structuralKeys) {
            if (payload.has(key)) {
                checkStructuralRedactionMarkers(payload.opt(key), key, payloadFingerprint)
            }
        }

        // 6. Credential check across entire serialized string
        val payloadStr = payload.toString()
        if (payloadStr.contains("sk-or-") || payloadStr.contains("Bearer ")) {
            throw OpenRouterException(
                statusCode = null,
                userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Outbound request payload contains credential-like patterns. Raw secrets must never enter protocol fields.",
                failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                fieldPath = "payload",
                validationReason = "Payload contains credential-like patterns.",
                originalPayloadFingerprint = payloadFingerprint,
            )
        }

        // 7. Reject protocol-level internal keys (metadata minimization)
        val internalProtocolKeys = setOf(
            "metadata", "local_metadata", "goal_id", "task_id",
            "agent_role", "selection_reason", "logical_request_id",
            "recovery_plan_id", "exchange_id"
        )
        for (key in internalProtocolKeys) {
            if (payload.has(key)) {
                throw OpenRouterException(
                    statusCode = null,
                    userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Outbound wire payload contains internal OpenAssistant metadata key: '$key'.",
                    failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                    fieldPath = key,
                    validationReason = "Internal OpenAssistant metadata reached the provider wire boundary.",
                    originalPayloadFingerprint = payloadFingerprint,
                )
            }
        }
    }

    private fun checkStructuralRedactionMarkers(value: Any?, currentPath: String, payloadFingerprint: String) {
        when (value) {
            is String -> {
                if (value.contains("[REDACTED]") || value.contains("[EXCLUDED]")) {
                    throw OpenRouterException(
                        statusCode = null,
                        userMessage = "Local validation failed (LOCAL_REQUEST_SCHEMA_FAILURE): Outbound request structural field '$currentPath' contains a diagnostic redaction marker: '$value'.",
                        failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
                        fieldPath = currentPath,
                        validationReason = "Structural field contains diagnostic redaction marker.",
                        originalPayloadFingerprint = payloadFingerprint,
                    )
                }
            }
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    checkStructuralRedactionMarkers(value.opt(k), "$currentPath.$k", payloadFingerprint)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    checkStructuralRedactionMarkers(value.opt(i), "$currentPath[$i]", payloadFingerprint)
                }
            }
        }
    }

    /**
     * Defensive repair operating on a copy of the canonical payload.
     * Normalizes string reasoning into a proper JSON object with "effort".
     */
    fun repairReasoningShapeOnCopy(canonicalPayload: JSONObject): JSONObject? {
        if (!canonicalPayload.has("reasoning")) return null
        val rawReasoning = canonicalPayload.opt("reasoning")
        if (rawReasoning is JSONObject && rawReasoning.has("effort")) return null // Already valid

        val repaired = JSONObject(canonicalPayload.toString())
        val effortValue = when (rawReasoning) {
            is String -> rawReasoning.ifBlank { "medium" }
            is JSONObject -> rawReasoning.optString("effort", "medium")
            else -> "medium"
        }
        repaired.put("reasoning", JSONObject().put("effort", effortValue))
        return repaired
    }

    /**
     * Sanitizes raw wire payload or response string for safe research monitor records.
     */
    fun sanitizeForDiagnostics(rawText: String): String {
        return redactResearchMonitorText(rawText)
    }
}
