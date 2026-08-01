package com.david.openassistant.domain.model

import com.david.openassistant.data.openrouter.OpenRouterModel

object ModelProfileSelector {
    fun choose(
        profile: ModelProfile,
        models: List<OpenRouterModel>,
        currentModelId: String? = null,
    ): OpenRouterModel? {
        val textModels = models.filter(OpenRouterModel::supportsTextChat)
        if (textModels.isEmpty()) return null
        
        return when (profile) {
            ModelProfile.MANUAL -> textModels.firstOrNull { it.id == currentModelId } ?: textModels.firstOrNull()
            ModelProfile.AUTO -> textModels.firstOrNull { it.id == "openrouter/auto-beta" } ?: textModels.firstOrNull()
            ModelProfile.FREE -> textModels.firstOrNull { it.id == "openrouter/free" } ?: textModels.firstOrNull()
        }
    }
}
