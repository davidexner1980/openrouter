package com.david.openassistant.agent

import com.david.openassistant.domain.tools.SafeToolDefinition
import java.util.Locale


internal data class FocusedToolSelection(
    val definitions: List<SafeToolDefinition>,
    val preferredToolName: String?,
)

internal fun capabilityScopedToolDefinitions(
    definitions: List<SafeToolDefinition>,
    maximumDefinitions: Int = 96,
): List<SafeToolDefinition> = definitions.take(maximumDefinitions)

/**
 * Full execution starts with the entire applicable catalog. Narrowing is a
 * recovery technique used only after a model skipped a required tool call;
 * it must never become an artificial limit on what a fresh milestone can do.
 */
internal fun executionToolSelection(
    task: AgentTask,
    definitions: List<SafeToolDefinition>,
    focusedRecovery: Boolean,
): FocusedToolSelection = if (focusedRecovery) {
    focusedToolSelection(task, definitions, MAX_FOCUSED_TOOL_DEFINITIONS)
} else {
    FocusedToolSelection(
        definitions = capabilityScopedToolDefinitions(
            definitions = definitions,
            maximumDefinitions = MAX_FULL_LOCAL_TOOL_DEFINITIONS,
        ),
        preferredToolName = null,
    )
}

/**
 * Keeps a tool milestone small enough for routed models to make a deliberate
 * choice. The prior runtime attached every registered schema (49 in the phone
 * replay), which let a provider return polished prose without choosing any
 * tool. Intent rules choose obvious deterministic operations; lexical ranking
 * retains custom recipe tools and uncommon operations without sending the
 * entire registry.
 */
internal fun focusedToolSelection(
    task: AgentTask,
    definitions: List<SafeToolDefinition>,
    maximumDefinitions: Int = MAX_FOCUSED_TOOL_DEFINITIONS,
): FocusedToolSelection {
    if (maximumDefinitions <= 0 || definitions.isEmpty()) {
        return FocusedToolSelection(emptyList(), null)
    }

    val availableByName = definitions.associateBy { it.name }
    val taskText = buildString {
        appendLine(task.title)
        appendLine(task.instructions)
        task.acceptanceCriteria.forEach { appendLine(it.description) }
    }.lowercase(Locale.US)
    val intentNames = TOOL_INTENT_RULES
        .asSequence()
        .filter { rule -> rule.pattern.containsMatchIn(taskText) }
        .flatMap { rule -> rule.toolNames.asSequence() }
        .filter(availableByName::containsKey)
        .distinct()
        .toList()
    val taskTokens = taskText.toolTokens()
    val lexicalMatches = definitions
        .mapIndexed { index, definition ->
            val definitionText = buildString {
                appendLine(definition.name.replace('_', ' '))
                appendLine(definition.displayName)
                appendLine(definition.description)
                definition.parameters.forEach { parameter ->
                    appendLine(parameter.name.replace('_', ' '))
                    appendLine(parameter.description)
                }
            }.lowercase(Locale.US)
            val overlap = taskTokens.intersect(definitionText.toolTokens()).size
            val exactNameBonus = if (taskText.contains(definition.name.replace('_', ' '))) 4 else 0
            RankedTool(definition.name, overlap + exactNameBonus, index)
        }
        .filter { it.score > 0 }
        .sortedWith(compareByDescending<RankedTool> { it.score }.thenBy { it.originalIndex })

    val foundryNames = if (task.capability == AgentCapability.TOOL_CREATE) {
        listOf("create_tool_recipe", "list_tool_recipes", "disable_tool_recipe")
            .filter(availableByName::containsKey)
    } else {
        emptyList()
    }
    val activeRecipeNames = definitions
        .asSequence()
        .map { it.name }
        .filter { it.startsWith("recipe_") }
        .take(MAX_ACTIVE_RECIPE_CANDIDATES)
        .toList()
    val fallbackNames = GENERAL_TOOL_FALLBACK_NAMES.filter(availableByName::containsKey)
    val selectedNames = (foundryNames + intentNames + lexicalMatches.map { it.name } + activeRecipeNames + fallbackNames)
        .distinct()
        .take(maximumDefinitions)
    val selected = selectedNames.mapNotNull(availableByName::get)
    val preferred = when (task.capability) {
        AgentCapability.TOOL_CREATE -> "create_tool_recipe".takeIf(selectedNames::contains)
        AgentCapability.TOOL_USE -> intentNames.firstOrNull { it in selectedNames }
            ?: lexicalMatches.firstOrNull { it.score >= MIN_LEXICAL_FORCE_SCORE && it.name in selectedNames }?.name
        else -> null
    }
    return FocusedToolSelection(selected, preferred)
}

private data class RankedTool(
    val name: String,
    val score: Int,
    val originalIndex: Int,
)

private data class ToolIntentRule(
    val pattern: Regex,
    val toolNames: List<String>,
)

private fun String.toolTokens(): Set<String> = TOOL_TOKEN_PATTERN
    .findAll(this)
    .map { it.value }
    .filter { it.length >= 3 && it !in TOOL_STOP_WORDS }
    .toSet()

internal const val MAX_FOCUSED_TOOL_DEFINITIONS = 12
internal const val MAX_FULL_LOCAL_TOOL_DEFINITIONS = 96
private const val MAX_ACTIVE_RECIPE_CANDIDATES = 3
private const val MIN_LEXICAL_FORCE_SCORE = 2

private val TOOL_TOKEN_PATTERN = Regex("[a-z0-9]+")
private val TOOL_STOP_WORDS = setOf(
    "and", "are", "for", "from", "into", "local", "must", "only", "result", "that", "the", "this",
    "tool", "use", "using", "with",
)
private val GENERAL_TOOL_FALLBACK_NAMES = listOf(
    "list_tool_recipes",
    "calculate",
    "statistics",
    "count_text",
    "current_date_time",
    "unit_convert",
    "public_web_search",
    "sandbox_workbench",
)
private val TOOL_INTENT_RULES = listOf(
    ToolIntentRule(
        Regex("\\b(price|pricing|cost|value|ratio|percent|percentage|discount|markup|margin|rate|roi|average|mean|median|sum|total|budget|numeric|number)\\b"),
        listOf("calculate", "percentage_change", "statistics"),
    ),
    ToolIntentRule(
        Regex("\\b(csv|table|tabular|spreadsheet|dataset|data set|row|rows|column|columns)\\b"),
        listOf("csv_summary", "csv_to_markdown", "statistics"),
    ),
    ToolIntentRule(
        Regex("\\b(date|dates|deadline|duration|calendar|business day|timezone|time zone|timestamp|current time)\\b"),
        listOf("current_date_time", "date_difference", "date_add", "business_days_between", "timezone_convert"),
    ),
    ToolIntentRule(
        Regex("\\b(convert|conversion|unit|units|temperature|distance|length|weight|mass|volume)\\b"),
        listOf("unit_convert"),
    ),
    ToolIntentRule(
        Regex("\\b(json|jsonl|object|array|schema)\\b"),
        listOf("format_json", "json_get", "json_compare", "json_merge"),
    ),
    ToolIntentRule(
        Regex("\\b(diff|difference|compare text|changed lines|before and after)\\b"),
        listOf("text_diff", "list_compare", "json_compare", "checksum_compare"),
    ),
    ToolIntentRule(
        Regex("\\b(text|word|words|regex|pattern|extract|replace|sort|deduplicate|frequency)\\b"),
        listOf("count_text", "find_text", "extract_regex", "replace_regex", "sort_unique_lines", "word_frequency"),
    ),
    ToolIntentRule(
        Regex("\\b(url|urls|link|links|source audit|https|web search|search the web|fetch)\\b"),
        listOf("extract_urls", "source_url_audit", "public_web_search", "public_web_fetch"),
    ),
    ToolIntentRule(
        Regex("\\b(file|files|folder|workspace|read file|write file|file info)\\b"),
        listOf("workspace_list_files", "workspace_file_info", "workspace_read_text", "workspace_search_text", "workspace_write_text"),
    ),
    ToolIntentRule(
        Regex("\\b(code|script|program|compile|build|test|tests|execute|sandbox|data analysis)\\b"),
        listOf("sandbox_workbench"),
    ),
    ToolIntentRule(
        Regex("\\b(hash|checksum|sha256|digest)\\b"),
        listOf("sha256_text", "checksum_compare"),
    ),
    ToolIntentRule(
        Regex("\\b(html|entity|escape|unescape)\\b"),
        listOf("html_escape", "html_unescape"),
    ),
    ToolIntentRule(
        Regex("\\b(uuid|identifier|slug)\\b"),
        listOf("generate_uuid", "slugify"),
    ),
    ToolIntentRule(
        Regex("\\b(recipe|reusable workflow|existing tool)\\b"),
        listOf("list_tool_recipes"),
    ),
)
