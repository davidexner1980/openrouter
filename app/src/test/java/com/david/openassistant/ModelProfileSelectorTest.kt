package com.david.openassistant

import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.ModelProfile
import com.david.openassistant.domain.model.ModelProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileSelectorTest {
    private val models = listOf(
        model(
            id = "openrouter/free",
            name = "Free Models Router",
            free = true,
            tools = true,
        ),
        model(
            id = "vendor/flash-mini",
            name = "Flash Mini",
            promptPrice = 0.0000001,
            completionPrice = 0.0000002,
        ),
        model(
            id = "vendor/deep-reasoning-pro",
            name = "Deep Reasoning Pro",
            reasoning = true,
        ),
        model(
            id = "vendor/code-coder",
            name = "Code Coder",
            description = "Programming and software model",
            tools = true,
        ),
        model(
            id = "vendor/vision-vl",
            name = "Vision VL",
            vision = true,
        ),
    )

    @Test
    fun freeProfilePrefersFreeRouter() {
        assertEquals(
            "openrouter/free",
            ModelProfileSelector.choose(ModelProfile.FREE, models)?.id,
        )
    }

    @Test
    fun manualProfilePreservesCurrentModel() {
        assertEquals(
            "vendor/deep-reasoning-pro",
            ModelProfileSelector.choose(
                profile = ModelProfile.MANUAL,
                models = models,
                currentModelId = "vendor/deep-reasoning-pro",
            )?.id,
        )
    }

    @Test
    fun profilesNeverSelectExplicitlyNonTextModels() {
        val audioOnly = model(
            id = "google/lyria-3-clip-preview",
            name = "Lyria",
            structuredOutputs = true,
            outputModalities = listOf("audio"),
        )

        assertEquals(
            "vendor/flash-mini",
            ModelProfileSelector.choose(
                profile = ModelProfile.MANUAL,
                models = listOf(audioOnly, models[1]),
                currentModelId = audioOnly.id,
            )?.id,
        )
    }

    @Test
    fun missingArchitectureMetadataRemainsTextCompatible() {
        val legacyRouter = model(
            id = "openrouter/auto-beta",
            name = "Auto",
            inputModalities = emptyList(),
            outputModalities = emptyList(),
        )

        assertTrue(legacyRouter.supportsTextChat)
        assertEquals(legacyRouter.id, ModelProfileSelector.choose(ModelProfile.AUTO, listOf(legacyRouter))?.id)
    }

    private fun model(
        id: String,
        name: String,
        description: String = "",
        free: Boolean = false,
        tools: Boolean = false,
        vision: Boolean = false,
        reasoning: Boolean = false,
        structuredOutputs: Boolean = false,
        inputModalities: List<String> = if (vision) listOf("text", "image") else listOf("text"),
        outputModalities: List<String> = listOf("text"),
        promptPrice: Double? = if (free) 0.0 else 0.000001,
        completionPrice: Double? = if (free) 0.0 else 0.000002,
    ) = OpenRouterModel(
        id = id,
        name = name,
        description = description,
        contextLength = 128_000,
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        supportedParameters = buildSet {
            if (tools) add("tools")
            if (reasoning) add("reasoning")
            if (structuredOutputs) add("structured_outputs")
        },
        promptPricePerToken = promptPrice,
        completionPricePerToken = completionPrice,
    )
}
