package com.david.openassistant.domain.model

import com.david.openassistant.data.openrouter.OpenRouterModel
import kotlin.math.ln

object ModelProfileSelector {
    fun choose(
        profile: ModelProfile,
        models: List<OpenRouterModel>,
        currentModelId: String? = null,
    ): OpenRouterModel? {
        val textModels = models.filter(OpenRouterModel::supportsTextChat)
        if (textModels.isEmpty()) return null
        if (profile == ModelProfile.MANUAL) {
            return textModels.firstOrNull { it.id == currentModelId } ?: textModels.firstOrNull()
        }

        exactRouter(profile, textModels)?.let { return it }

        // Profiles AUTO, FREE, and BODY_BUILDER must strictly use their respective routers.
        if (profile in setOf(ModelProfile.AUTO, ModelProfile.FREE, ModelProfile.BODY_BUILDER)) {
            return null
        }

        val candidates = when (profile) {
            ModelProfile.FREE -> textModels.filter { it.isFree }
            ModelProfile.VISION -> textModels.filter { it.supportsVision }
            else -> textModels
        }.ifEmpty { textModels }

        return candidates.maxWithOrNull(
            compareBy<OpenRouterModel> { score(profile, it) }
                .thenByDescending { it.id },
        )
    }

    private fun exactRouter(
        profile: ModelProfile,
        models: List<OpenRouterModel>,
    ): OpenRouterModel? = when (profile) {
        ModelProfile.AUTO -> models.firstOrNull { it.id == AgentModelSelector.AUTO_BETA_ROUTER_MODEL_ID }
        ModelProfile.FREE -> models.firstOrNull { it.id == AgentModelSelector.FREE_ROUTER_MODEL_ID }
        ModelProfile.BODY_BUILDER -> models.firstOrNull { it.id == AgentModelSelector.BODY_BUILDER_MODEL_ID }
        else -> null
    }

    private fun score(profile: ModelProfile, model: OpenRouterModel): Double {
        val searchable = "${model.id} ${model.name} ${model.description}".lowercase()
        var score = 0.0

        when (profile) {
            ModelProfile.AUTO -> {
                if (model.id == AgentModelSelector.AUTO_BETA_ROUTER_MODEL_ID) score += 1000.0
            }

            ModelProfile.BODY_BUILDER -> {
                if (model.id == AgentModelSelector.BODY_BUILDER_MODEL_ID) score += 1000.0
            }

            ModelProfile.FREE -> {
                if (model.isFree) score += 100.0
                if (model.supportsTools) score += 10.0
                if (model.contextLength >= 64_000) score += 2.0
            }

            ModelProfile.FAST -> {
                score += keywordScore(
                    searchable,
                    mapOf(
                        "nano" to 18.0,
                        "mini" to 16.0,
                        "flash" to 16.0,
                        "small" to 12.0,
                        "fast" to 12.0,
                        "turbo" to 8.0,
                        "lite" to 8.0,
                    ),
                )
                score += affordabilityScore(model)
                if (model.contextLength in 1..128_000) score += 2.0
            }

            ModelProfile.DEEP -> {
                score += keywordScore(
                    searchable,
                    mapOf(
                        "reasoning" to 20.0,
                        "deep" to 18.0,
                        "think" to 16.0,
                        "r1" to 14.0,
                        "pro" to 7.0,
                        "large" to 5.0,
                    ),
                )
                if (model.supportedParameters.any { it.equals("reasoning", ignoreCase = true) }) {
                    score += 24.0
                }
                if (model.contextLength >= 128_000) score += 5.0
            }

            ModelProfile.CODING -> {
                score += keywordScore(
                    searchable,
                    mapOf(
                        "coder" to 24.0,
                        "coding" to 24.0,
                        "code" to 18.0,
                        "program" to 12.0,
                        "developer" to 10.0,
                        "software" to 8.0,
                    ),
                )
                if (model.supportsTools) score += 5.0
            }

            ModelProfile.VISION -> {
                if (model.supportsVision) score += 100.0
                score += keywordScore(
                    searchable,
                    mapOf(
                        "vision" to 18.0,
                        "vl" to 12.0,
                        "multimodal" to 12.0,
                        "pro" to 4.0,
                    ),
                )
                if (model.contextLength >= 64_000) score += 3.0
            }

            ModelProfile.MANUAL -> Unit
        }

        if (model.id.contains(":free")) score += 1.0
        return score
    }

    private fun keywordScore(text: String, weights: Map<String, Double>): Double =
        weights.entries.sumOf { (keyword, weight) -> if (text.contains(keyword)) weight else 0.0 }

    private fun affordabilityScore(model: OpenRouterModel): Double {
        if (model.isFree) return 20.0
        val totalPerMillion = listOfNotNull(
            model.promptPricePerToken,
            model.completionPricePerToken,
        ).sum() * 1_000_000.0
        if (totalPerMillion <= 0.0) return 0.0
        return (12.0 - ln(1.0 + totalPerMillion)).coerceAtLeast(0.0)
    }
}
