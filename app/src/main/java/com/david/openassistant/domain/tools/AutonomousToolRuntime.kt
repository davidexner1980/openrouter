package com.david.openassistant.domain.tools

import android.content.Context
import com.david.openassistant.agent.ProviderRequestLedger
import com.david.openassistant.agent.RequestState
import com.david.openassistant.agent.ToolCountSource
import com.david.openassistant.agent.ToolCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.network.ResearchWebSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val MAX_TOOL_RECIPE_LIST = 40

/**
 * Unified autonomous tool registry. Deterministic transforms and the private
 * workspace run on-device without runtime permissions. sandbox_workbench is
 * the sole network-backed function tool and delegates computation to an
 * isolated OpenRouter-hosted container; it never runs shell commands on the
 * Android device. No tool can grant permissions, launch arbitrary apps,
 * install executable code, or mutate application policy.
 */
open class AutonomousToolRuntime internal constructor(
    private val context: Context?,
    private val researchMonitor: ResearchMonitor?,
    private val recipeStore: ToolRecipeStore?,
    private val recipeEngine: ToolRecipeEngine?,
    private val safeExecutor: SafeToolExecutor?,
    private val advancedExecutor: AdvancedToolExecutor?,
    private val deviceToolRuntime: DeviceToolRuntime?,
    private val workspaceRuntime: WorkspaceToolRuntime?,
    private val runtimeDiagnosticToolRuntime: RuntimeDiagnosticToolRuntime?,
    private val hostedSandboxRuntime: HostedSandboxToolRuntime?,
    private val researchWebSettings: ResearchWebSettings?,
    private val publicWebToolRuntime: PublicWebToolRuntime?,
) : ToolCountSource {
    constructor(context: Context) : this(
        context = context,
        researchMonitor = ResearchMonitor(context.applicationContext),
        recipeStore = ToolRecipeStore(context.applicationContext),
        recipeEngine = ToolRecipeEngine(),
        safeExecutor = SafeToolExecutor(),
        advancedExecutor = AdvancedToolExecutor(),
        deviceToolRuntime = DeviceToolRuntime(context.applicationContext),
        workspaceRuntime = WorkspaceToolRuntime(context.applicationContext),
        runtimeDiagnosticToolRuntime = RuntimeDiagnosticToolRuntime(context.applicationContext),
        hostedSandboxRuntime = HostedSandboxToolRuntime(researchMonitor = ResearchMonitor(context.applicationContext)),
        researchWebSettings = ResearchWebSettings(context.applicationContext),
        publicWebToolRuntime = PublicWebToolRuntime(
            networkConfigProvider = ResearchWebSettings(context.applicationContext)::load,
            researchMonitor = ResearchMonitor(context.applicationContext),
        )
    )

    fun cancelActiveCalls() {
        publicWebToolRuntime!!.cancelActiveCalls()
        hostedSandboxRuntime!!.cancelActiveCalls()
    }

    open fun definitions(): List<SafeToolDefinition> {
        val activeRecipes = recipeStore!!.load()
            .filter { it.status == ToolRecipeStatus.ACTIVE }
            .map(::recipeDefinition)
        return SafeToolCatalog.definitions +
            AdvancedToolCatalog.definitions +
            DeviceToolCatalog.definitions +
            WorkspaceToolCatalog.definitions +
            RuntimeDiagnosticToolCatalog.definitions +
            PublicWebToolCatalog.definitions +
            HostedSandboxToolCatalog.definitions +
            activeRecipes
    }

    open fun isNetworkAvailable(): Boolean {
        return com.david.openassistant.agent.AgentOperationalState.isNetworkAvailable(context)
    }

    open fun isPublicWebConfigured(): Boolean {
        return researchWebSettings?.load()?.searxngBaseUrl != null
    }

    open suspend fun execute(call: OpenRouterToolCall): ToolExecutionResult = execute(
        call = call,
        apiKey = null,
        modelId = null,
    )

    open suspend fun execute(
        call: OpenRouterToolCall,
        apiKey: String?,
        modelId: String?,
        goal: com.david.openassistant.agent.AgentGoal? = null,
        taskId: String? = null,
    ): ToolExecutionResult {
        val correlationId = call.id.ifBlank { "tool-${UUID.randomUUID()}" }
        val startedAt = System.currentTimeMillis()
        ProviderRequestLedger.start(correlationId)
        researchMonitor!!.record(
            category = "tool",
            event = "call_started",
            correlationId = correlationId,
            goalId = goal?.id,
            taskId = taskId,
            fields = mapOf(
                "tool_call_id" to call.id,
                "tool_name" to call.name,
                "display_name" to describe(call),
                "arguments_json" to call.argumentsJson,
                "requested_model" to modelId,
                "openrouter_credential_available" to !apiKey.isNullOrBlank(),
            ),
        )
        return try {
            executeInternal(call, apiKey, modelId, goal, taskId).let { result ->
                val durationMs = System.currentTimeMillis() - startedAt
                if (ProviderRequestLedger.terminalize(correlationId, RequestState.COMPLETED)) {
                    researchMonitor.record(
                        category = "tool",
                        event = "call_completed",
                        correlationId = correlationId,
                        goalId = goal?.id,
                        taskId = taskId,
                        durationMs = durationMs,
                        fields = mapOf(
                            "tool_call_id" to call.id,
                            "tool_name" to call.name,
                            "duration_ms" to durationMs,
                            "display_summary" to result.displaySummary,
                            "output_json" to result.outputJson,
                            "prompt_tokens" to result.promptTokens,
                            "completion_tokens" to result.completionTokens,
                            "total_tokens" to result.totalTokens,
                            "cost_usd" to result.costUsd,
                            "web_search_requests" to result.webSearchRequests,
                        ),
                    )
                }
                result.copy(durationMs = durationMs)
            }
        } catch (error: Throwable) {
            val terminalState = if (error is kotlinx.coroutines.CancellationException) {
                RequestState.CANCELLED
            } else {
                RequestState.FAILED
            }
            if (ProviderRequestLedger.terminalize(correlationId, terminalState)) {
                researchMonitor.record(
                    category = "tool",
                    event = "call_failed",
                    level = "ERROR",
                    correlationId = correlationId,
                    goalId = goal?.id,
                    taskId = taskId,
                    durationMs = (System.currentTimeMillis() - startedAt),
                    fields = mapOf(
                        "tool_call_id" to call.id,
                        "tool_name" to call.name,
                        "duration_ms" to (System.currentTimeMillis() - startedAt),
                        "error_type" to error::class.java.name,
                        "error_message" to error.message.orEmpty(),
                    ),
                )
            }
            throw error
        } finally {
            ProviderRequestLedger.clear(correlationId)
        }
    }

    private suspend fun executeInternal(
        call: OpenRouterToolCall,
        apiKey: String?,
        modelId: String?,
        goal: com.david.openassistant.agent.AgentGoal? = null,
        taskId: String? = null,
    ): ToolExecutionResult = when (call.name) {
        "create_tool_recipe" -> createRecipe(call.argumentsJson)
        "list_tool_recipes" -> listRecipes()
        "disable_tool_recipe" -> disableRecipe(call.argumentsJson)
        else -> {
            when {
                WorkspaceToolCatalog.handles(call.name) -> workspaceRuntime!!.execute(call)
                DeviceToolCatalog.handles(call.name) -> deviceToolRuntime!!.execute(call)
                RuntimeDiagnosticToolCatalog.handles(call.name) -> runtimeDiagnosticToolRuntime!!.execute(call)
                PublicWebToolCatalog.handles(call.name) -> publicWebToolRuntime!!.execute(
                    call = call, 
                    goalId = goal?.id, 
                    taskId = taskId, 
                    blockedSources = goal?.blockedSources ?: emptyList()
                )
                AdvancedToolCatalog.handles(call.name) -> advancedExecutor!!.execute(call)
                HostedSandboxToolCatalog.handles(call.name) -> hostedSandboxRuntime!!.execute(
                    call = call, 
                    apiKey = apiKey, 
                    modelId = modelId, 
                    goalId = goal?.id, 
                    taskId = taskId
                )
                else -> {
                val recipe = recipeStore!!.load().firstOrNull {
                    it.status == ToolRecipeStatus.ACTIVE && it.openRouterToolName == call.name
                }
                if (recipe != null) executeRecipe(recipe, call.argumentsJson) else safeExecutor!!.execute(call)
            }
            }
        }
    }

    fun describe(call: OpenRouterToolCall): String {
        val definition = definitions().firstOrNull { it.name == call.name }
            ?: return call.name
        return definition.displayName
    }

    override suspend fun loadToolCounts(): ToolCounts = withContext(Dispatchers.IO) {
        val recipeCount = recipeStore!!.load().count { it.status == ToolRecipeStatus.ACTIVE }
        val workspaceCount = workspaceRuntime!!.fileCount()
        ToolCounts(recipeCount, workspaceCount)
    }

    private fun createRecipe(argumentsJson: String): ToolExecutionResult {
        val args = parseArguments(argumentsJson)
        val rawRecipe = requiredString(args, "recipe_json")
        val proposed = ToolRecipeCodec.parseRecipeJson(rawRecipe)
        val existingNames = definitions().mapTo(mutableSetOf()) { it.name }
        val existingRecipe = recipeStore!!.load().firstOrNull { it.openRouterToolName == proposed.openRouterToolName }
        val namesForValidation = if (existingRecipe == null) {
            existingNames
        } else {
            existingNames - existingRecipe.openRouterToolName
        }
        val validation = ToolRecipeValidator.validate(proposed, namesForValidation)
        if (!validation.valid) {
            throw ToolValidationException(
                "The proposed tool recipe was rejected: ${validation.errors.joinToString(" ")}",
            )
        }
        val tests = recipeEngine!!.runTests(proposed)
        val failures = tests.filterNot { it.passed }
        if (failures.isNotEmpty()) {
            throw ToolValidationException(
                "The proposed tool recipe failed deterministic tests: ${failures.joinToString { it.message }}",
            )
        }
        val activated = proposed.copy(
            id = existingRecipe?.id ?: proposed.id,
            status = ToolRecipeStatus.ACTIVE,
            createdAt = existingRecipe?.createdAt ?: proposed.createdAt,
            updatedAt = System.currentTimeMillis(),
            version = (existingRecipe?.version ?: 0) + 1,
        )
        recipeStore.upsert(activated)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("status", "active")
                .put("tool_name", activated.openRouterToolName)
                .put("display_name", activated.displayName)
                .put("version", activated.version)
                .put("tests_passed", tests.size)
                .put("allowed_operations", JSONArray(ToolRecipeOperation.entries.map { it.wireName }))
                .put("composable_builtin_tools", JSONArray(RecipeBuiltinToolCatalog.names))
                .toString(),
            displaySummary = "Created ${activated.openRouterToolName}; ${tests.size} deterministic test(s) passed.",
        )
    }

    private fun listRecipes(): ToolExecutionResult {
        val recipes = recipeStore!!.load().take(MAX_TOOL_RECIPE_LIST)
        val array = JSONArray().apply {
            recipes.forEach { recipe ->
                put(
                    JSONObject()
                        .put("tool_name", recipe.openRouterToolName)
                        .put("display_name", recipe.displayName)
                        .put("description", recipe.description)
                        .put("status", recipe.status.name.lowercase())
                        .put("version", recipe.version)
                        .put("step_count", recipe.steps.size)
                        .put("test_count", recipe.tests.size),
                )
            }
        }
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("count", recipes.size)
                .put("recipes", array)
                .toString(),
            displaySummary = "${recipes.size} reusable tool recipe(s) are stored.",
        )
    }

    private fun disableRecipe(argumentsJson: String): ToolExecutionResult {
        val args = parseArguments(argumentsJson)
        val toolName = requiredString(args, "tool_name")
        val existing = recipeStore!!.load().firstOrNull { it.openRouterToolName == toolName }
            ?: throw ToolValidationException("No recipe tool named '$toolName' exists.")
        recipeStore.disable(toolName)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("status", "disabled")
                .put("tool_name", existing.openRouterToolName)
                .toString(),
            displaySummary = "Disabled ${existing.openRouterToolName}.",
        )
    }

    private fun executeRecipe(recipe: ToolRecipe, argumentsJson: String): ToolExecutionResult {
        val args = parseArguments(argumentsJson)
        val inputs = recipe.parameters.associateBy({ it.name }, { parameter -> args.optString(parameter.name) })
        val output = recipeEngine!!.execute(recipe, inputs)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("tool_name", recipe.openRouterToolName)
                .put("version", recipe.version)
                .put("output", output)
                .toString(),
            displaySummary = "${recipe.displayName} completed (${output.length} output characters).",
        )
    }

    private fun recipeDefinition(recipe: ToolRecipe): SafeToolDefinition = SafeToolDefinition(
        name = recipe.openRouterToolName,
        displayName = recipe.displayName,
        description = "Reusable local tool created and activated by the Tool Foundry after deterministic validation. ${recipe.description}",
        parameters = recipe.parameters.map { parameter ->
            ToolParameter(
                name = parameter.name,
                description = parameter.description,
                required = parameter.required,
            )
        },
    )

    private fun parseArguments(raw: String): JSONObject = parseToolArguments(raw)

    private fun requiredString(arguments: JSONObject, name: String): String {
        val value = arguments.optString(name).trim()
        if (value.isBlank() || value == "null") throw ToolValidationException("Missing required tool argument: $name.")
        return value
    }
}
