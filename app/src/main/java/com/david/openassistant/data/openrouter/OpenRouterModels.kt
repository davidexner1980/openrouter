package com.david.openassistant.data.openrouter

import java.util.Locale

data class OpenRouterModel(
    val id: String,
    val name: String,
    val description: String,
    val contextLength: Int,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val supportedParameters: Set<String>,
    val promptPricePerToken: Double?,
    val completionPricePerToken: Double?,
) {
    val provider: String
        get() = id.substringBefore('/').replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.getDefault()) else character.toString()
        }

    val supportsVision: Boolean
        get() = inputModalities.any { it.equals("image", ignoreCase = true) }

    val supportsTools: Boolean
        get() = supportedParameters.any { it.equals("tools", ignoreCase = true) }

    val supportsStructuredOutputs: Boolean
        get() = supportedParameters.any {
            it.equals("structured_outputs", ignoreCase = true) ||
                it.equals("response_format", ignoreCase = true)
        }

    /**
     * OpenRouter occasionally omits architecture metadata for compatibility
     * routers and legacy catalog entries. Missing modality metadata therefore
     * remains text-compatible, while an explicit audio/image-only declaration
     * is never routed into chat or autonomous work.
     */
    val acceptsTextInput: Boolean
        get() = inputModalities.isEmpty() || inputModalities.any { it.equals("text", ignoreCase = true) }

    val producesTextOutput: Boolean
        get() = outputModalities.isEmpty() || outputModalities.any { it.equals("text", ignoreCase = true) }

    val supportsTextChat: Boolean
        get() = acceptsTextInput && producesTextOutput && !isDedicatedMediaGenerator

    /**
     * Catalog modality metadata can be incomplete. A dedicated music, image,
     * speech, or video generator must never be promoted into the autonomous
     * planner merely because it advertises response_format. This is a model
     * capability boundary, not a subject-matter routing template.
     */
    val isDedicatedMediaGenerator: Boolean
        get() {
            val normalized = "$id $name".lowercase(Locale.US)
            return DEDICATED_MEDIA_MODEL_MARKERS.any(normalized::contains)
        }

    val supportsAgentTools: Boolean
        get() = supportsTextChat && supportsTools

    val supportsAgentPlanning: Boolean
        get() = supportsTextChat && supportsStructuredOutputs

    val isFree: Boolean
        get() = promptPricePerToken == 0.0 && completionPricePerToken == 0.0

    fun priceSummary(): String {
        if (id == "openrouter/auto-beta") return "Routed-model pricing"
        if (id == "openrouter/free") return "Free"
        if (id == "openrouter/bodybuilder") return "Request generation free; generated request execution may have routed-model cost"
        if (isFree) return "Free"
        val prompt = promptPricePerToken?.times(1_000_000.0)?.takeIf { it >= 0.0 }
        val completion = completionPricePerToken?.times(1_000_000.0)?.takeIf { it >= 0.0 }
        return when {
            prompt != null && completion != null -> "$${"%.2f".format(prompt)} in / $${"%.2f".format(completion)} out per 1M"
            prompt != null -> "$${"%.2f".format(prompt)} input per 1M"
            completion != null -> "$${"%.2f".format(completion)} output per 1M"
            else -> "Pricing unavailable"
        }
    }
}

private val DEDICATED_MEDIA_MODEL_MARKERS = listOf(
    "/lyria",
    "lyria-",
    "/imagen",
    "imagen-",
    "/veo",
    "veo-",
    "/sora",
    "sora-",
    "dall-e",
    "stable-diffusion",
    "/whisper",
    "/tts",
    "text-to-speech",
)

data class OpenRouterKeyInfo(
    val label: String,
    val isFreeTier: Boolean,
    val usage: Double?,
    val limit: Double?,
    val limitRemaining: Double?,
    val expiresAt: String?,
)

enum class ChatRole(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

enum class ChatAttachmentKind {
    IMAGE,
    PDF,
}

data class ChatAttachment(
    val id: String,
    val kind: ChatAttachmentKind,
    val displayName: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    val pageCount: Int = 0,
)

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val isStreaming: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList(),
)

data class StreamSummary(
    val responseId: String? = null,
    val resolvedModel: String? = null,
    val finishReason: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val cost: Double? = null,
)



data class AutomaticToolExecution(
    val toolName: String,
    val displaySummary: String,
    val succeeded: Boolean,
)

data class AutomaticToolLoopResult(
    val content: String,
    val summary: StreamSummary,
    val executions: List<AutomaticToolExecution>,
)
