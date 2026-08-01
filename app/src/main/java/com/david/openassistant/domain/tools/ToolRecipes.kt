package com.david.openassistant.domain.tools

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val MAX_RECIPE_COUNT = 40
private const val MAX_RECIPE_STEPS = 10
private const val MAX_RECIPE_PARAMETERS = 12
private const val MAX_RECIPE_TESTS = 8
private const val MAX_RECIPE_JSON_CHARS = 48_000
private const val MAX_TOOL_TEXT_CHARS = 64_000
private const val MAX_TEMPLATE_CHARS = 16_000
private const val MAX_RECIPE_OUTPUT_CHARS = 64_000
private const val RECIPE_FILE_VERSION = 1
private const val RECIPE_TOOL_PREFIX = "recipe_"

enum class ToolRecipeStatus {
    ACTIVE,
    DISABLED,
}

enum class ToolRecipeOperation(val wireName: String) {
    TRIM("trim"),
    NORMALIZE_WHITESPACE("normalize_whitespace"),
    LOWERCASE("lowercase"),
    UPPERCASE("uppercase"),
    REGEX_EXTRACT("regex_extract"),
    REGEX_REPLACE("regex_replace"),
    SORT_LINES("sort_lines"),
    UNIQUE_LINES("unique_lines"),
    COUNT_TEXT("count_text"),
    SHA256("sha256"),
    RENDER_TEMPLATE("render_template"),
    CALCULATE("calculate"),
    JSON_GET("json_get"),
    INVOKE_BUILTIN("invoke_builtin");

    companion object {
        fun fromWireName(value: String): ToolRecipeOperation =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
                ?: throw ToolValidationException("Unsupported recipe operation: $value")
    }
}

/**
 * Deterministic built-ins that a generated recipe may compose. Tools that are
 * time-varying, random, network-backed, workspace-mutating, or Tool Foundry
 * controls are deliberately excluded.
 */
object RecipeBuiltinToolCatalog {
    private val excludedSafeTools = setOf(
        "current_date_time",
        "generate_uuid",
        "create_tool_recipe",
        "list_tool_recipes",
        "disable_tool_recipe",
    )

    val definitions: List<SafeToolDefinition> = (
        SafeToolCatalog.definitions.filterNot { it.name in excludedSafeTools } +
            AdvancedToolCatalog.definitions
        ).distinctBy { it.name }

    val names: List<String> = definitions.map { it.name }.sorted()

    fun find(name: String): SafeToolDefinition? = definitions.firstOrNull { it.name == name }

    fun execute(call: OpenRouterToolCall): ToolExecutionResult = when {
        AdvancedToolCatalog.handles(call.name) -> AdvancedToolExecutor().execute(call)
        find(call.name) != null -> SafeToolExecutor().execute(call)
        else -> throw ToolValidationException("Recipe built-in '${call.name}' is not approved.")
    }
}

data class ToolRecipeParameter(
    val name: String,
    val description: String,
    val required: Boolean = true,
)

data class ToolRecipeStep(
    val id: String,
    val operation: ToolRecipeOperation,
    val arguments: Map<String, String>,
)

data class ToolRecipeTest(
    val inputs: Map<String, String>,
    val expectedOutput: String? = null,
    val expectedContains: String? = null,
)

data class ToolRecipe(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val displayName: String,
    val description: String,
    val parameters: List<ToolRecipeParameter>,
    val steps: List<ToolRecipeStep>,
    val outputTemplate: String,
    val tests: List<ToolRecipeTest>,
    val status: ToolRecipeStatus = ToolRecipeStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val version: Int = 1,
) {
    val openRouterToolName: String
        get() = RECIPE_TOOL_PREFIX + toolName
}

data class ToolRecipeValidationResult(
    val valid: Boolean,
    val errors: List<String>,
)

data class ToolRecipeTestResult(
    val passed: Boolean,
    val message: String,
)

object ToolRecipeCodec {
    fun parseRecipeJson(raw: String): ToolRecipe {
        if (raw.length > MAX_RECIPE_JSON_CHARS) {
            throw ToolValidationException("Tool recipe JSON is limited to $MAX_RECIPE_JSON_CHARS characters.")
        }
        val root = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw ToolValidationException("The proposed tool recipe is not valid JSON.")
        }
        return parseRecipe(root)
    }

    fun parseRecipe(root: JSONObject): ToolRecipe {
        val rawName = root.optString("tool_name").trim()
        val toolName = sanitizeToolName(rawName)
        val parameters = root.optJSONArray("parameters").jsonObjects().map { item ->
            ToolRecipeParameter(
                name = sanitizeParameterName(item.optString("name")),
                description = item.optString("description").trim().take(500),
                required = item.optBoolean("required", true),
            )
        }
        val steps = root.optJSONArray("steps").jsonObjects().map { item ->
            ToolRecipeStep(
                id = sanitizeStepId(item.optString("id")),
                operation = ToolRecipeOperation.fromWireName(item.optString("operation")),
                arguments = item.optJSONObject("arguments").toStringMap(),
            )
        }
        val tests = root.optJSONArray("tests").jsonObjects().map { item ->
            ToolRecipeTest(
                inputs = item.optJSONObject("inputs").toStringMap(),
                expectedOutput = item.optString("expected_output")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.take(MAX_RECIPE_OUTPUT_CHARS),
                expectedContains = item.optString("expected_contains")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.take(MAX_RECIPE_OUTPUT_CHARS),
            )
        }
        return ToolRecipe(
            id = root.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            toolName = toolName,
            displayName = root.optString("display_name").trim().take(100),
            description = root.optString("description").trim().take(1_000),
            parameters = parameters,
            steps = steps,
            outputTemplate = root.optString("output_template").take(MAX_TEMPLATE_CHARS),
            tests = tests,
            status = runCatching { ToolRecipeStatus.valueOf(root.optString("status", "ACTIVE")) }
                .getOrDefault(ToolRecipeStatus.ACTIVE),
            createdAt = root.optLong("created_at", System.currentTimeMillis()),
            updatedAt = root.optLong("updated_at", System.currentTimeMillis()),
            version = root.optInt("version", 1),
        )
    }

    fun toJson(recipe: ToolRecipe): JSONObject = JSONObject()
        .put("id", recipe.id)
        .put("tool_name", recipe.toolName)
        .put("display_name", recipe.displayName)
        .put("description", recipe.description)
        .put(
            "parameters",
            JSONArray().apply {
                recipe.parameters.forEach { parameter ->
                    put(
                        JSONObject()
                            .put("name", parameter.name)
                            .put("description", parameter.description)
                            .put("required", parameter.required),
                    )
                }
            },
        )
        .put(
            "steps",
            JSONArray().apply {
                recipe.steps.forEach { step ->
                    put(
                        JSONObject()
                            .put("id", step.id)
                            .put("operation", step.operation.wireName)
                            .put("arguments", JSONObject(step.arguments)),
                    )
                }
            },
        )
        .put("output_template", recipe.outputTemplate)
        .put(
            "tests",
            JSONArray().apply {
                recipe.tests.forEach { test ->
                    put(
                        JSONObject()
                            .put("inputs", JSONObject(test.inputs))
                            .put("expected_output", test.expectedOutput ?: JSONObject.NULL)
                            .put("expected_contains", test.expectedContains ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put("status", recipe.status.name)
        .put("created_at", recipe.createdAt)
        .put("updated_at", recipe.updatedAt)
        .put("version", recipe.version)

    private fun sanitizeToolName(value: String): String {
        val sanitized = value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(48)
        if (sanitized.isBlank()) throw ToolValidationException("A recipe tool_name is required.")
        if (sanitized.startsWith(RECIPE_TOOL_PREFIX)) {
            throw ToolValidationException("Recipe tool_name must not include the reserved '$RECIPE_TOOL_PREFIX' prefix.")
        }
        return sanitized
    }

    private fun sanitizeParameterName(value: String): String {
        val sanitized = value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(48)
        if (sanitized.isBlank()) throw ToolValidationException("Every recipe parameter needs a valid name.")
        return sanitized
    }

    private fun sanitizeStepId(value: String): String {
        val sanitized = value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(48)
        if (sanitized.isBlank()) throw ToolValidationException("Every recipe step needs a valid id.")
        return sanitized
    }

    private fun JSONArray?.jsonObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> optString(key) }
    }
}

object ToolRecipeValidator {
    private val placeholderPattern = Regex("\\$\\{(input|step)\\.([A-Za-z0-9_-]+)(?:\\.output)?\\}")

    fun validate(
        recipe: ToolRecipe,
        existingOpenRouterNames: Set<String> = emptySet(),
    ): ToolRecipeValidationResult {
        val errors = buildList {
            if (recipe.displayName.isBlank()) add("display_name is required.")
            if (recipe.description.isBlank()) add("description is required.")
            if (recipe.parameters.isEmpty()) add("At least one parameter is required.")
            if (recipe.parameters.size > MAX_RECIPE_PARAMETERS) {
                add("A recipe may have at most $MAX_RECIPE_PARAMETERS parameters.")
            }
            if (recipe.parameters.map { it.name }.distinct().size != recipe.parameters.size) {
                add("Recipe parameter names must be unique.")
            }
            if (recipe.steps.isEmpty()) add("At least one recipe step is required.")
            if (recipe.steps.size > MAX_RECIPE_STEPS) add("A recipe may have at most $MAX_RECIPE_STEPS steps.")
            if (recipe.steps.map { it.id }.distinct().size != recipe.steps.size) {
                add("Recipe step ids must be unique.")
            }
            if (recipe.tests.isEmpty()) add("At least one deterministic test is required before activation.")
            if (recipe.tests.size > MAX_RECIPE_TESTS) add("A recipe may have at most $MAX_RECIPE_TESTS tests.")
            if (recipe.outputTemplate.isBlank()) add("output_template is required.")
            if (recipe.outputTemplate.length > MAX_TEMPLATE_CHARS) add("output_template is too large.")
            if (recipe.openRouterToolName in existingOpenRouterNames) {
                add("A tool named ${recipe.openRouterToolName} already exists.")
            }

            val parameterNames = recipe.parameters.mapTo(mutableSetOf()) { it.name }
            val stepIds = recipe.steps.mapTo(mutableSetOf()) { it.id }
            val availableStepIds = mutableSetOf<String>()
            recipe.steps.forEach { step ->
                step.arguments.forEach { (argumentName, value) ->
                    if (argumentName.isBlank()) add("Step ${step.id} has a blank argument name.")
                    validateReferences(value, parameterNames, availableStepIds).forEach(::add)
                    if (argumentName in setOf("pattern", "replacement") && value.length > 300) {
                        add("Regex-related argument in step ${step.id} is too long.")
                    }
                    if (value.length > MAX_TEMPLATE_CHARS) add("Argument $argumentName in step ${step.id} is too large.")
                }
                validateOperationArguments(step).forEach(::add)
                if (step.operation == ToolRecipeOperation.INVOKE_BUILTIN) {
                    validateBuiltinInvocation(step).forEach(::add)
                }
                availableStepIds += step.id
            }
            validateReferences(recipe.outputTemplate, parameterNames, stepIds).forEach(::add)

            recipe.tests.forEachIndexed { index, test ->
                val unknownInputs = test.inputs.keys - parameterNames
                if (unknownInputs.isNotEmpty()) add("Test ${index + 1} has unknown inputs: ${unknownInputs.joinToString()}.")
                if (test.expectedOutput == null && test.expectedContains == null) {
                    add("Test ${index + 1} needs expected_output or expected_contains.")
                }
            }
        }.distinct()
        return ToolRecipeValidationResult(errors.isEmpty(), errors)
    }

    private fun validateReferences(
        value: String,
        parameterNames: Set<String>,
        availableStepIds: Set<String>,
    ): List<String> = buildList {
        placeholderPattern.findAll(value).forEach { match ->
            val kind = match.groupValues[1]
            val name = match.groupValues[2]
            when {
                kind == "input" && name !in parameterNames -> add("Unknown input placeholder: $name.")
                kind == "step" && name !in availableStepIds -> add("Unknown or forward step placeholder: $name.")
            }
        }
    }

    private fun validateOperationArguments(step: ToolRecipeStep): List<String> {
        val required = when (step.operation) {
            ToolRecipeOperation.TRIM,
            ToolRecipeOperation.NORMALIZE_WHITESPACE,
            ToolRecipeOperation.LOWERCASE,
            ToolRecipeOperation.UPPERCASE,
            ToolRecipeOperation.SORT_LINES,
            ToolRecipeOperation.UNIQUE_LINES,
            ToolRecipeOperation.COUNT_TEXT,
            ToolRecipeOperation.SHA256,
            -> setOf("text")

            ToolRecipeOperation.REGEX_EXTRACT -> setOf("text", "pattern")
            ToolRecipeOperation.REGEX_REPLACE -> setOf("text", "pattern", "replacement")
            ToolRecipeOperation.RENDER_TEMPLATE -> setOf("template")
            ToolRecipeOperation.CALCULATE -> setOf("expression")
            ToolRecipeOperation.JSON_GET -> setOf("json", "path")
            ToolRecipeOperation.INVOKE_BUILTIN -> setOf("tool_name")
        }
        return (required - step.arguments.keys).map { missing ->
            "Step ${step.id} (${step.operation.wireName}) is missing argument '$missing'."
        }
    }

    private fun validateBuiltinInvocation(step: ToolRecipeStep): List<String> = buildList {
        val toolName = step.arguments["tool_name"].orEmpty().trim()
        if (toolName.isBlank()) return@buildList
        if (placeholderPattern.containsMatchIn(toolName)) {
            add("Step ${step.id} must use a fixed approved tool_name; dynamic tool selection is not allowed.")
            return@buildList
        }
        val definition = RecipeBuiltinToolCatalog.find(toolName)
        if (definition == null) {
            add("Step ${step.id} requests unapproved recipe built-in '$toolName'.")
            return@buildList
        }
        val allowedArguments = definition.parameters.mapTo(mutableSetOf()) { it.name } + "tool_name"
        val unknownArguments = step.arguments.keys - allowedArguments
        if (unknownArguments.isNotEmpty()) {
            add("Step ${step.id} has unsupported argument(s) for $toolName: ${unknownArguments.sorted().joinToString()}.")
        }
        definition.parameters.filter { it.required && it.name !in step.arguments }.forEach { parameter ->
            add("Step ${step.id} ($toolName) is missing required argument '${parameter.name}'.")
        }
    }
}

class ToolRecipeEngine {
    private val placeholderPattern = Regex("\\$\\{(input|step)\\.([A-Za-z0-9_-]+)(?:\\.output)?\\}")

    fun execute(recipe: ToolRecipe, inputs: Map<String, String>): String {
        val requiredMissing = recipe.parameters.filter { it.required && inputs[it.name].isNullOrBlank() }
        if (requiredMissing.isNotEmpty()) {
            throw ToolValidationException("Missing recipe input(s): ${requiredMissing.joinToString { it.name }}.")
        }
        inputs.values.forEach { value ->
            if (value.length > MAX_TOOL_TEXT_CHARS) {
                throw ToolValidationException("Recipe inputs are limited to $MAX_TOOL_TEXT_CHARS characters each.")
            }
        }
        val safeInputs = recipe.parameters.associate { parameter ->
            parameter.name to inputs[parameter.name].orEmpty()
        }
        val stepOutputs = linkedMapOf<String, String>()
        recipe.steps.forEach { step ->
            val resolved = step.arguments.mapValues { (_, value) -> resolve(value, safeInputs, stepOutputs) }
            val output = executeOperation(step.operation, resolved).take(MAX_RECIPE_OUTPUT_CHARS)
            stepOutputs[step.id] = output
        }
        return resolve(recipe.outputTemplate, safeInputs, stepOutputs).take(MAX_RECIPE_OUTPUT_CHARS)
    }

    fun runTests(recipe: ToolRecipe): List<ToolRecipeTestResult> = recipe.tests.mapIndexed { index, test ->
        runCatching { execute(recipe, test.inputs) }
            .fold(
                onSuccess = { output ->
                    val exactPassed = test.expectedOutput?.let { output == it } ?: true
                    val containsPassed = test.expectedContains?.let(output::contains) ?: true
                    ToolRecipeTestResult(
                        passed = exactPassed && containsPassed,
                        message = if (exactPassed && containsPassed) {
                            "Test ${index + 1} passed."
                        } else {
                            "Test ${index + 1} failed: output did not match the expected assertion."
                        },
                    )
                },
                onFailure = { error ->
                    ToolRecipeTestResult(false, "Test ${index + 1} failed: ${error.message.orEmpty()}")
                },
            )
    }

    private fun resolve(
        template: String,
        inputs: Map<String, String>,
        stepOutputs: Map<String, String>,
    ): String = placeholderPattern.replace(template) { match ->
        when (match.groupValues[1]) {
            "input" -> inputs[match.groupValues[2]].orEmpty()
            "step" -> stepOutputs[match.groupValues[2]].orEmpty()
            else -> ""
        }
    }

    private fun executeOperation(operation: ToolRecipeOperation, args: Map<String, String>): String = when (operation) {
        ToolRecipeOperation.TRIM -> args.getValue("text").trim()
        ToolRecipeOperation.NORMALIZE_WHITESPACE -> args.getValue("text")
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        ToolRecipeOperation.LOWERCASE -> args.getValue("text").lowercase(Locale.US)
        ToolRecipeOperation.UPPERCASE -> args.getValue("text").uppercase(Locale.US)
        ToolRecipeOperation.REGEX_EXTRACT -> {
            val pattern = validateSafeRegexPattern(args.getValue("pattern"))
            val text = args.getValue("text")
            if (text.length > 20_000) throw ToolValidationException("Recipe regex text is limited to 20000 characters.")
            val group = args["group"]?.toIntOrNull()?.coerceIn(0, 20) ?: 0
            Regex(pattern).findAll(text)
                .mapNotNull { match -> match.groups[group]?.value }
                .joinToString("\n")
        }

        ToolRecipeOperation.REGEX_REPLACE -> {
            val pattern = validateSafeRegexPattern(args.getValue("pattern"))
            val text = args.getValue("text")
            if (text.length > 20_000) throw ToolValidationException("Recipe regex text is limited to 20000 characters.")
            Regex(pattern).replace(text, args.getValue("replacement"))
        }

        ToolRecipeOperation.SORT_LINES -> args.getValue("text")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sorted()
            .joinToString("\n")

        ToolRecipeOperation.UNIQUE_LINES -> args.getValue("text")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString("\n")

        ToolRecipeOperation.COUNT_TEXT -> {
            val text = args.getValue("text")
            JSONObject()
                .put("characters", text.length)
                .put("words", Regex("\\S+").findAll(text).count())
                .put("lines", if (text.isEmpty()) 0 else text.lineSequence().count())
                .toString()
        }

        ToolRecipeOperation.SHA256 -> sha256(args.getValue("text"))
        ToolRecipeOperation.RENDER_TEMPLATE -> args.getValue("template")
        ToolRecipeOperation.CALCULATE -> {
            val value = calculateExpression(args.getValue("expression"))
            java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        }

        ToolRecipeOperation.JSON_GET -> jsonGet(args.getValue("json"), args.getValue("path"))
        ToolRecipeOperation.INVOKE_BUILTIN -> invokeBuiltin(args)
    }

    private fun invokeBuiltin(args: Map<String, String>): String {
        val toolName = args.getValue("tool_name").trim()
        val definition = RecipeBuiltinToolCatalog.find(toolName)
            ?: throw ToolValidationException("Recipe built-in '$toolName' is not approved.")
        val arguments = JSONObject()
        definition.parameters.forEach { parameter ->
            if (parameter.name in args) arguments.put(parameter.name, args.getValue(parameter.name))
        }
        return RecipeBuiltinToolCatalog.execute(
            OpenRouterToolCall(
                id = "recipe_builtin_$toolName",
                name = toolName,
                argumentsJson = arguments.toString(),
            ),
        ).outputJson.take(MAX_RECIPE_OUTPUT_CHARS)
    }

    private fun jsonGet(rawJson: String, rawPath: String): String {
        var current: Any = try {
            if (rawJson.trim().startsWith("[")) JSONArray(rawJson) else JSONObject(rawJson)
        } catch (_: Exception) {
            throw ToolValidationException("json_get received invalid JSON.")
        }
        val path = rawPath.split('.').filter(String::isNotBlank)
        path.forEach { segment ->
            val container = current
            current = when (container) {
                is JSONObject -> container.opt(segment) ?: JSONObject.NULL
                is JSONArray -> container.opt(segment.toIntOrNull() ?: -1) ?: JSONObject.NULL
                else -> JSONObject.NULL
            }
            if (current == JSONObject.NULL) throw ToolValidationException("json_get could not find path '$rawPath'.")
        }
        return when (current) {
            is JSONObject, is JSONArray -> current.toString()
            else -> current.toString()
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

class ToolRecipeStore(context: Context) {
    private val directory = File(context.filesDir, "autonomy").apply { mkdirs() }
    private val atomicFile = AtomicFile(File(directory, "tool_recipes.json"))

    @Synchronized
    fun load(): List<ToolRecipe> {
        if (!atomicFile.baseFile.exists()) return emptyList()
        return runCatching {
            val raw = atomicFile.openRead().use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
            val root = JSONObject(raw)
            val version = root.optInt("file_version", 0)
            if (version != RECIPE_FILE_VERSION) return@runCatching emptyList<ToolRecipe>()
            val recipes = root.optJSONArray("recipes") ?: JSONArray()
            buildList {
                for (index in 0 until recipes.length()) {
                    recipes.optJSONObject(index)?.let { item ->
                        runCatching { ToolRecipeCodec.parseRecipe(item) }.getOrNull()?.let(::add)
                    }
                }
            }.take(MAX_RECIPE_COUNT)
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun upsert(recipe: ToolRecipe): List<ToolRecipe> {
        val current = load().filterNot { it.id == recipe.id || it.openRouterToolName == recipe.openRouterToolName }
        val next = (current + recipe.copy(updatedAt = System.currentTimeMillis()))
            .sortedByDescending { it.updatedAt }
            .take(MAX_RECIPE_COUNT)
        save(next)
        return next
    }

    @Synchronized
    fun disable(openRouterToolName: String): List<ToolRecipe> {
        val next = load().map { recipe ->
            if (recipe.openRouterToolName == openRouterToolName) {
                recipe.copy(status = ToolRecipeStatus.DISABLED, updatedAt = System.currentTimeMillis())
            } else {
                recipe
            }
        }
        save(next)
        return next
    }

    @Synchronized
    private fun save(recipes: List<ToolRecipe>) {
        val payload = JSONObject()
            .put("file_version", RECIPE_FILE_VERSION)
            .put(
                "recipes",
                JSONArray().apply { recipes.forEach { put(ToolRecipeCodec.toJson(it)) } },
            )
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val output = atomicFile.startWrite()
        try {
            output.write(payload)
            output.flush()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}
