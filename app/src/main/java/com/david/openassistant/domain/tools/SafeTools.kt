package com.david.openassistant.domain.tools

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

private const val MAX_EXPRESSION_LENGTH = 256
private const val MAX_TOOL_TEXT_LENGTH = 64_000
private const val MAX_REGEX_TEXT_LENGTH = 20_000
private const val MAX_REGEX_LENGTH = 300
private const val MAX_TOOL_OUTPUT_LENGTH = 64_000
private const val MAX_STATISTIC_VALUES = 10_000

data class SafeToolDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: List<ToolParameter>,
)

data class ToolParameter(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = true,
)

data class OpenRouterToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolExecutionResult(
    val outputJson: String,
    val displaySummary: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val costUsd: Double = 0.0,
    val webSearchRequests: Int = 0,
)

object SafeToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "calculate",
            displayName = "Calculator",
            description = "Evaluate a bounded mathematical expression locally. Supports parentheses, +, -, *, /, %, and ^. No files, network, apps, or permissions are available.",
            parameters = listOf(ToolParameter("expression", "Mathematical expression, for example (1250 * 0.0825) + 30.")),
        ),
        SafeToolDefinition(
            name = "current_date_time",
            displayName = "Current date and time",
            description = "Read the Android device clock for an optional IANA time zone. No location or network access is used.",
            parameters = listOf(ToolParameter("timezone", "Optional IANA time zone such as America/Chicago.", required = false)),
        ),
        SafeToolDefinition(
            name = "date_difference",
            displayName = "Date difference",
            description = "Calculate the signed number of days between two ISO dates locally.",
            parameters = listOf(
                ToolParameter("start_date", "Start date in YYYY-MM-DD format."),
                ToolParameter("end_date", "End date in YYYY-MM-DD format."),
            ),
        ),
        SafeToolDefinition(
            name = "unit_convert",
            displayName = "Unit converter",
            description = "Convert common length, mass, volume, time, and temperature units deterministically.",
            parameters = listOf(
                ToolParameter("value", "Numeric value to convert."),
                ToolParameter("from_unit", "Source unit, such as ft, m, lb, kg, gal, l, f, or c."),
                ToolParameter("to_unit", "Destination unit."),
            ),
        ),
        SafeToolDefinition(
            name = "statistics",
            displayName = "Statistics",
            description = "Calculate count, sum, minimum, maximum, mean, and median for supplied numbers.",
            parameters = listOf(ToolParameter("numbers", "Numbers separated by commas, spaces, semicolons, or new lines.")),
        ),
        SafeToolDefinition(
            name = "count_text",
            displayName = "Text counter",
            description = "Count characters, words, non-whitespace characters, and lines in supplied text.",
            parameters = listOf(ToolParameter("text", "Text to count.")),
        ),
        SafeToolDefinition(
            name = "find_text",
            displayName = "Literal text finder",
            description = "Find literal text occurrences with line and column positions. It does not interpret a regular expression.",
            parameters = listOf(
                ToolParameter("text", "Text to inspect."),
                ToolParameter("query", "Literal text to find."),
                ToolParameter("case_sensitive", "Optional true or false. Defaults to false.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "extract_regex",
            displayName = "Safe pattern extractor",
            description = "Extract matches from supplied text using a bounded, screened regular expression. Backreferences, lookbehind, and suspicious nested quantifiers are rejected.",
            parameters = listOf(
                ToolParameter("text", "Text to inspect, limited to 20,000 characters for regex operations."),
                ToolParameter("pattern", "Kotlin regular expression, limited to 300 characters and screened for high-risk constructs."),
                ToolParameter("group", "Optional capture-group number. Defaults to 0.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "replace_regex",
            displayName = "Safe pattern replacement",
            description = "Apply a bounded, screened regular-expression replacement to supplied text locally.",
            parameters = listOf(
                ToolParameter("text", "Text to transform, limited to 20,000 characters for regex operations."),
                ToolParameter("pattern", "Kotlin regular expression, limited to 300 characters and screened for high-risk constructs."),
                ToolParameter("replacement", "Replacement text."),
            ),
        ),
        SafeToolDefinition(
            name = "sort_unique_lines",
            displayName = "Line organizer",
            description = "Trim, deduplicate, and alphabetically sort non-empty lines in supplied text.",
            parameters = listOf(ToolParameter("text", "Lines to organize.")),
        ),
        SafeToolDefinition(
            name = "format_json",
            displayName = "JSON formatter",
            description = "Validate and pretty-print a supplied JSON object or array.",
            parameters = listOf(
                ToolParameter("json", "JSON object or array text."),
                ToolParameter("indent", "Optional indentation width from 0 to 8. Defaults to 2.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "json_get",
            displayName = "JSON path reader",
            description = "Read a dot-separated object or array path from JSON supplied to the tool.",
            parameters = listOf(
                ToolParameter("json", "JSON object or array text."),
                ToolParameter("path", "Dot-separated path such as customer.address.city or items.0.name."),
            ),
        ),
        SafeToolDefinition(
            name = "sha256_text",
            displayName = "SHA-256 text digest",
            description = "Calculate a SHA-256 digest of supplied text locally.",
            parameters = listOf(ToolParameter("text", "Text to hash.")),
        ),
        SafeToolDefinition(
            name = "base64_encode",
            displayName = "Base64 encoder",
            description = "Encode supplied UTF-8 text as Base64 locally.",
            parameters = listOf(ToolParameter("text", "Text to encode.")),
        ),
        SafeToolDefinition(
            name = "base64_decode",
            displayName = "Base64 decoder",
            description = "Decode supplied Base64 into bounded UTF-8 text locally.",
            parameters = listOf(ToolParameter("base64", "Base64 text to decode.")),
        ),
        SafeToolDefinition(
            name = "url_encode",
            displayName = "URL component encoder",
            description = "Percent-encode supplied UTF-8 text for a URL query component.",
            parameters = listOf(ToolParameter("text", "Text to encode.")),
        ),
        SafeToolDefinition(
            name = "url_decode",
            displayName = "URL component decoder",
            description = "Decode a percent-encoded URL query component locally.",
            parameters = listOf(ToolParameter("text", "Text to decode.")),
        ),
        SafeToolDefinition(
            name = "generate_uuid",
            displayName = "UUID generator",
            description = "Generate one or more random version-4 UUID values locally.",
            parameters = listOf(ToolParameter("count", "Optional count from 1 to 20. Defaults to 1.", required = false)),
        ),
        SafeToolDefinition(
            name = "create_tool_recipe",
            displayName = "Tool Foundry",
            description = "Create or update a reusable local workflow tool. Recipes activate only after structural validation and every included deterministic test passes. Recipe JSON fields: tool_name, display_name, description, parameters[{name,description,required}], steps[{id,operation,arguments}], output_template, tests[{inputs,expected_output or expected_contains}]. Allowed operations: trim, normalize_whitespace, lowercase, uppercase, regex_extract, regex_replace, sort_lines, unique_lines, count_text, sha256, render_template, calculate, json_get, and invoke_builtin. invoke_builtin requires a fixed approved tool_name plus that tool's normal named arguments, so recipes can safely compose deterministic date, number, JSON, CSV, URL, text, and analysis tools. Placeholders use \${'\$'}input.name and \${'\$'}step.step_id.",
            parameters = listOf(ToolParameter("recipe_json", "Complete recipe JSON as a string, including representative and edge-case deterministic tests.")),
        ),
        SafeToolDefinition(
            name = "list_tool_recipes",
            displayName = "Tool inventory",
            description = "List reusable Tool Foundry recipes and their active or disabled status.",
            parameters = emptyList(),
        ),
        SafeToolDefinition(
            name = "disable_tool_recipe",
            displayName = "Disable recipe tool",
            description = "Disable a reusable recipe tool without deleting its audit record.",
            parameters = listOf(ToolParameter("tool_name", "Full generated tool name, for example recipe_normalize_parts.")),
        ),
    )
}

class SafeToolExecutor {
    fun execute(call: OpenRouterToolCall): ToolExecutionResult {
        val arguments = parseArguments(call.argumentsJson)
        return when (call.name) {
            "calculate" -> executeCalculator(arguments)
            "current_date_time" -> executeCurrentDateTime(arguments)
            "date_difference" -> executeDateDifference(arguments)
            "unit_convert" -> executeUnitConvert(arguments)
            "statistics" -> executeStatistics(arguments)
            "count_text" -> executeCountText(arguments)
            "find_text" -> executeFindText(arguments)
            "extract_regex" -> executeRegexExtract(arguments)
            "replace_regex" -> executeRegexReplace(arguments)
            "sort_unique_lines" -> executeSortUniqueLines(arguments)
            "format_json" -> executeFormatJson(arguments)
            "json_get" -> executeJsonGet(arguments)
            "sha256_text" -> executeSha256(arguments)
            "base64_encode" -> executeBase64Encode(arguments)
            "base64_decode" -> executeBase64Decode(arguments)
            "url_encode" -> executeUrlEncode(arguments)
            "url_decode" -> executeUrlDecode(arguments)
            "generate_uuid" -> executeGenerateUuid(arguments)
            "create_tool_recipe", "list_tool_recipes", "disable_tool_recipe" ->
                throw ToolValidationException("Tool Foundry operations require the autonomous tool runtime.")
            else -> throw ToolValidationException("The requested tool is not available.")
        }
    }

    private fun executeCalculator(arguments: JSONObject): ToolExecutionResult {
        val expression = requiredString(arguments, "expression")
        val value = calculateExpression(expression)
        val formatted = formatNumber(value)
        return ToolExecutionResult(
            outputJson = JSONObject().put("expression", expression).put("result", formatted).toString(),
            displaySummary = "$expression = $formatted",
        )
    }

    private fun executeCurrentDateTime(arguments: JSONObject): ToolExecutionResult {
        val requestedZone = optionalString(arguments, "timezone")
        val zone = try {
            requestedZone?.let(ZoneId::of) ?: ZoneId.systemDefault()
        } catch (_: DateTimeException) {
            throw ToolValidationException("Unknown time zone: $requestedZone")
        }
        val current = ZonedDateTime.now(zone)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("timezone", zone.id)
                .put("iso", current.format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
                .put("date", current.toLocalDate().toString())
                .put("time", current.toLocalTime().withNano(0).toString())
                .put("utc_offset", current.offset.id)
                .toString(),
            displaySummary = "${current.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} ${zone.id}",
        )
    }

    private fun executeDateDifference(arguments: JSONObject): ToolExecutionResult {
        val start = parseIsoDate(requiredString(arguments, "start_date"), "start_date")
        val end = parseIsoDate(requiredString(arguments, "end_date"), "end_date")
        val days = ChronoUnit.DAYS.between(start, end)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("start_date", start.toString())
                .put("end_date", end.toString())
                .put("days", days)
                .put("absolute_days", abs(days))
                .toString(),
            displaySummary = "$days day(s) from $start to $end.",
        )
    }

    private fun executeUnitConvert(arguments: JSONObject): ToolExecutionResult {
        val value = requiredString(arguments, "value").toDoubleOrNull()
            ?: throw ToolValidationException("unit_convert requires a numeric value.")
        if (!value.isFinite()) throw ToolValidationException("unit_convert requires a finite numeric value.")
        val from = requiredString(arguments, "from_unit")
        val to = requiredString(arguments, "to_unit")
        val converted = convertUnits(value, from, to)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("value", value)
                .put("from_unit", normalizeUnit(from))
                .put("to_unit", normalizeUnit(to))
                .put("result", formatNumber(converted))
                .toString(),
            displaySummary = "${formatNumber(value)} $from = ${formatNumber(converted)} $to",
        )
    }

    private fun executeStatistics(arguments: JSONObject): ToolExecutionResult {
        val values = parseNumbers(requiredString(arguments, "numbers"))
        val sorted = values.sorted()
        val sum = values.sum()
        val mean = sum / values.size
        val median = if ((sorted.size % 2) == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[(sorted.size / 2) - 1] + sorted[sorted.size / 2]) / 2.0
        }
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("count", values.size)
                .put("sum", formatNumber(sum))
                .put("minimum", formatNumber(sorted.first()))
                .put("maximum", formatNumber(sorted.last()))
                .put("mean", formatNumber(mean))
                .put("median", formatNumber(median))
                .toString(),
            displaySummary = "Calculated statistics for ${values.size} value(s); mean ${formatNumber(mean)}, median ${formatNumber(median)}.",
        )
    }

    private fun executeCountText(arguments: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredString(arguments, "text"))
        val wordCount = Regex("\\S+").findAll(text).count()
        val lineCount = if (text.isEmpty()) 0 else text.lineSequence().count()
        val nonWhitespace = text.count { !it.isWhitespace() }
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("characters", text.length)
                .put("non_whitespace_characters", nonWhitespace)
                .put("words", wordCount)
                .put("lines", lineCount)
                .toString(),
            displaySummary = "${text.length} characters, $wordCount words, $lineCount lines.",
        )
    }

    private fun executeFindText(arguments: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredString(arguments, "text"))
        val query = requiredString(arguments, "query").take(2_000)
        val caseSensitive = optionalString(arguments, "case_sensitive")?.toBooleanStrictOrNull() ?: false
        val haystack = if (caseSensitive) text else text.lowercase(Locale.US)
        val needle = if (caseSensitive) query else query.lowercase(Locale.US)
        if (needle.isEmpty()) throw ToolValidationException("find_text requires a non-empty query.")
        val matches = JSONArray()
        var fromIndex = 0
        var count = 0
        while ((fromIndex <= (haystack.length - needle.length)) && (count < 200)) {
            val index = haystack.indexOf(needle, fromIndex)
            if (index < 0) break
            val line = text.take(index).count { it == '\n' } + 1
            val lineStart = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val column = index - lineStart + 1
            matches.put(JSONObject().put("index", index).put("line", line).put("column", column))
            count++
            fromIndex = index + needle.length.coerceAtLeast(1)
        }
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("query", query)
                .put("case_sensitive", caseSensitive)
                .put("count", count)
                .put("truncated", count >= 200)
                .put("matches", matches)
                .toString(),
            displaySummary = "Found $count occurrence(s) of '$query'.",
        )
    }

    private fun executeRegexExtract(arguments: JSONObject): ToolExecutionResult {
        val text = boundedRegexText(requiredString(arguments, "text"))
        val pattern = validateSafeRegexPattern(requiredString(arguments, "pattern"))
        val group = optionalString(arguments, "group")?.toIntOrNull()?.coerceIn(0, 20) ?: 0
        val regex = runCatching { Regex(pattern) }
            .getOrElse { error -> throw ToolValidationException("Invalid regular expression: ${error.message.orEmpty()}") }
        val matches = runCatching {
            regex.findAll(text)
                .take(1_000)
                .mapNotNull { match -> match.groups[group]?.value?.take(4_000) }
                .toList()
        }.getOrElse { error -> throw ToolValidationException("Regex extraction failed: ${error.message.orEmpty()}") }
        return ToolExecutionResult(
            outputJson = JSONObject().put("count", matches.size).put("matches", JSONArray(matches)).toString(),
            displaySummary = "Extracted ${matches.size} match(es).",
        )
    }

    private fun executeRegexReplace(arguments: JSONObject): ToolExecutionResult {
        val text = boundedRegexText(requiredString(arguments, "text"))
        val pattern = validateSafeRegexPattern(requiredString(arguments, "pattern"))
        val replacement = requiredString(arguments, "replacement").take(MAX_TOOL_OUTPUT_LENGTH)
        val output = runCatching { Regex(pattern).replace(text, replacement) }
            .getOrElse { error -> throw ToolValidationException("Regex replacement failed: ${error.message.orEmpty()}") }
            .take(MAX_TOOL_OUTPUT_LENGTH)
        return ToolExecutionResult(
            outputJson = JSONObject().put("output", output).toString(),
            displaySummary = "Pattern replacement produced ${output.length} characters.",
        )
    }

    private fun executeSortUniqueLines(arguments: JSONObject): ToolExecutionResult {
        val lines = boundedText(requiredString(arguments, "text"))
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .take(5_000)
            .toList()
        val output = lines.joinToString("\n").take(MAX_TOOL_OUTPUT_LENGTH)
        return ToolExecutionResult(
            outputJson = JSONObject().put("line_count", lines.size).put("output", output).toString(),
            displaySummary = "Organized ${lines.size} unique line(s).",
        )
    }

    private fun executeFormatJson(arguments: JSONObject): ToolExecutionResult {
        val raw = boundedText(requiredString(arguments, "json"))
        val indent = optionalString(arguments, "indent")?.toIntOrNull()?.coerceIn(0, 8) ?: 2
        val formatted = formatJson(raw, indent).take(MAX_TOOL_OUTPUT_LENGTH)
        return ToolExecutionResult(
            outputJson = JSONObject().put("valid", true).put("formatted", formatted).toString(),
            displaySummary = "Validated and formatted ${formatted.length} JSON characters.",
        )
    }

    private fun executeJsonGet(arguments: JSONObject): ToolExecutionResult {
        val rawJson = boundedText(requiredString(arguments, "json"))
        val path = requiredString(arguments, "path").take(1_000)
        var current: Any = parseJsonValue(rawJson)
        path.split('.').filter(String::isNotBlank).forEach { segment ->
            current = when (val value = current) {
                is JSONObject -> value.opt(segment) ?: JSONObject.NULL
                is JSONArray -> value.opt(segment.toIntOrNull() ?: -1) ?: JSONObject.NULL
                else -> JSONObject.NULL
            }
            if (current == JSONObject.NULL) throw ToolValidationException("JSON path '$path' was not found.")
        }
        val output = current.toString().take(MAX_TOOL_OUTPUT_LENGTH)
        return ToolExecutionResult(
            outputJson = JSONObject().put("path", path).put("value", current).toString(),
            displaySummary = "Read JSON path $path (${output.length} characters).",
        )
    }

    private fun executeSha256(arguments: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredString(arguments, "text"))
        val digest = sha256(text)
        return ToolExecutionResult(
            outputJson = JSONObject().put("sha256", digest).toString(),
            displaySummary = "SHA-256: $digest",
        )
    }

    private fun executeBase64Encode(arguments: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredString(arguments, "text"))
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        if (encoded.length > MAX_TOOL_OUTPUT_LENGTH) throw ToolValidationException("Encoded output exceeds the local tool output limit.")
        return ToolExecutionResult(
            outputJson = JSONObject().put("base64", encoded).toString(),
            displaySummary = "Encoded ${text.length} characters as Base64.",
        )
    }

    private fun executeBase64Decode(arguments: JSONObject): ToolExecutionResult {
        val encoded = boundedText(requiredString(arguments, "base64"))
        val decodedBytes = runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw ToolValidationException("base64_decode received invalid Base64 text.") }
        if (decodedBytes.size > MAX_TOOL_OUTPUT_LENGTH) throw ToolValidationException("Decoded output exceeds the local tool output limit.")
        val decoded = decodedBytes.toString(StandardCharsets.UTF_8)
        return ToolExecutionResult(
            outputJson = JSONObject().put("text", decoded).toString(),
            displaySummary = "Decoded ${decoded.length} UTF-8 characters.",
        )
    }

    private fun executeUrlEncode(arguments: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredString(arguments, "text"))
        val output = URLEncoder.encode(text, StandardCharsets.UTF_8.name()).take(MAX_TOOL_OUTPUT_LENGTH)
        return ToolExecutionResult(JSONObject().put("encoded", output).toString(), "URL-encoded ${text.length} characters.")
    }

    private fun executeUrlDecode(arguments: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredString(arguments, "text"))
        val output = runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8.name()) }
            .getOrElse { throw ToolValidationException("url_decode received invalid percent encoding.") }
            .take(MAX_TOOL_OUTPUT_LENGTH)
        return ToolExecutionResult(JSONObject().put("decoded", output).toString(), "URL-decoded ${output.length} characters.")
    }

    private fun executeGenerateUuid(arguments: JSONObject): ToolExecutionResult {
        val count = optionalString(arguments, "count")?.toIntOrNull()?.coerceIn(1, 20) ?: 1
        val values = List(count) { UUID.randomUUID().toString() }
        return ToolExecutionResult(
            JSONObject().put("count", count).put("uuids", JSONArray(values)).toString(),
            "Generated $count UUID value(s).",
        )
    }

    private fun boundedText(text: String): String {
        if (text.length > MAX_TOOL_TEXT_LENGTH) {
            throw ToolValidationException("Tool text input is limited to $MAX_TOOL_TEXT_LENGTH characters.")
        }
        return text
    }

    private fun boundedRegexText(text: String): String {
        if (text.length > MAX_REGEX_TEXT_LENGTH) {
            throw ToolValidationException("Regex text input is limited to $MAX_REGEX_TEXT_LENGTH characters.")
        }
        return text
    }

    private fun parseArguments(raw: String): JSONObject = parseToolArguments(raw)

    private fun requiredString(arguments: JSONObject, name: String): String {
        val value = optionalString(arguments, name)
        if (value.isNullOrBlank()) throw ToolValidationException("Missing required tool argument: $name.")
        return value
    }

    private fun optionalString(arguments: JSONObject, name: String): String? =
        arguments.optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
}

class ToolValidationException(message: String) : IllegalArgumentException(message)

/** Screen regular expressions before auto-execution to reduce ReDoS risk. */
fun validateSafeRegexPattern(pattern: String): String {
    if (pattern.length > MAX_REGEX_LENGTH) {
        throw ToolValidationException("Regex patterns are limited to $MAX_REGEX_LENGTH characters.")
    }
    if (Regex("\\\\[1-9]").containsMatchIn(pattern)) {
        throw ToolValidationException("Regex backreferences are not allowed in autonomous tools.")
    }
    if ("(?<=" in pattern || "(?<!" in pattern) {
        throw ToolValidationException("Regex lookbehind is not allowed in autonomous tools.")
    }
    if (Regex("\\((?:[^()]|\\\\.)*[+*](?:[^()]|\\\\.)*\\)[+*{]").containsMatchIn(pattern)) {
        throw ToolValidationException("Suspicious nested regex quantifiers are not allowed.")
    }
    if (Regex("\\((?:[^()]|\\\\.)*\\|(?:[^()]|\\\\.)*\\)[+*{]").containsMatchIn(pattern)) {
        throw ToolValidationException("Quantified regex alternation groups are not allowed in autonomous tools.")
    }
    if (pattern.contains(".*.*") || pattern.contains(".+.+") || pattern.count { it == '|' } > 30) {
        throw ToolValidationException("The regex is too ambiguous for autonomous execution.")
    }
    runCatching { Regex(pattern) }
        .getOrElse { error -> throw ToolValidationException("Invalid regular expression: ${error.message.orEmpty()}") }
    return pattern
}

fun calculateExpression(expression: String): Double {
    val normalized = expression.trim()
    if (normalized.isEmpty()) throw ToolValidationException("The calculator expression is empty.")
    if (normalized.length > MAX_EXPRESSION_LENGTH) {
        throw ToolValidationException("Calculator expressions are limited to $MAX_EXPRESSION_LENGTH characters.")
    }
    val value = ExpressionParser(normalized).parse()
    if (!value.isFinite()) throw ToolValidationException("The calculator result is not finite.")
    return value
}

private fun parseIsoDate(value: String, field: String): LocalDate = runCatching { LocalDate.parse(value) }
    .getOrElse { throw ToolValidationException("$field must use YYYY-MM-DD format.") }

private fun formatJson(raw: String, indent: Int): String = when (val value = parseJsonValue(raw)) {
    is JSONObject -> value.toString(indent)
    is JSONArray -> value.toString(indent)
    else -> value.toString()
}

private fun parseJsonValue(raw: String): Any = try {
    val trimmed = raw.trim()
    when {
        trimmed.startsWith("{") -> JSONObject(trimmed)
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> throw ToolValidationException("JSON input must be an object or array.")
    }
} catch (error: ToolValidationException) {
    throw error
} catch (_: Exception) {
    throw ToolValidationException("The supplied JSON is invalid.")
}

private fun parseNumbers(raw: String): List<Double> {
    val tokens = raw.split(Regex("[,;\\s]+"))
        .filter(String::isNotBlank)
    if (tokens.isEmpty()) throw ToolValidationException("statistics requires at least one number.")
    if (tokens.size > MAX_STATISTIC_VALUES) throw ToolValidationException("statistics is limited to $MAX_STATISTIC_VALUES values.")
    return tokens.map { token ->
        token.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: throw ToolValidationException("Invalid numeric value: ${token.take(80)}")
    }
}

private enum class UnitFamily { LENGTH, MASS, VOLUME, TIME, TEMPERATURE }
private data class UnitDefinition(val family: UnitFamily, val toBase: Double)

private val UNIT_ALIASES = mapOf(
    "millimeter" to "mm", "millimeters" to "mm",
    "centimeter" to "cm", "centimeters" to "cm",
    "meter" to "m", "meters" to "m", "metre" to "m", "metres" to "m",
    "kilometer" to "km", "kilometers" to "km", "kilometre" to "km", "kilometres" to "km",
    "inch" to "in", "inches" to "in",
    "foot" to "ft", "feet" to "ft",
    "yard" to "yd", "yards" to "yd",
    "mile" to "mi", "miles" to "mi",
    "milligram" to "mg", "milligrams" to "mg",
    "gram" to "g", "grams" to "g",
    "kilogram" to "kg", "kilograms" to "kg",
    "ounce" to "oz", "ounces" to "oz",
    "pound" to "lb", "pounds" to "lb", "lbs" to "lb",
    "ton" to "ton_us", "tons" to "ton_us", "us_ton" to "ton_us",
    "milliliter" to "ml", "milliliters" to "ml", "millilitre" to "ml", "millilitres" to "ml",
    "liter" to "l", "liters" to "l", "litre" to "l", "litres" to "l",
    "teaspoon" to "tsp", "teaspoons" to "tsp",
    "tablespoon" to "tbsp", "tablespoons" to "tbsp",
    "cups" to "cup",
    "pint" to "pt", "pints" to "pt",
    "quart" to "qt", "quarts" to "qt",
    "gallon" to "gal", "gallons" to "gal", "gallon_us" to "gal",
    "second" to "s", "seconds" to "s", "sec" to "s",
    "minute" to "min", "minutes" to "min",
    "hour" to "h", "hours" to "h", "hr" to "h",
    "day" to "d", "days" to "d",
    "celsius" to "c", "°c" to "c",
    "fahrenheit" to "f", "°f" to "f",
    "kelvin" to "k",
)

private val UNITS = mapOf(
    "mm" to UnitDefinition(UnitFamily.LENGTH, 0.001),
    "cm" to UnitDefinition(UnitFamily.LENGTH, 0.01),
    "m" to UnitDefinition(UnitFamily.LENGTH, 1.0),
    "km" to UnitDefinition(UnitFamily.LENGTH, 1_000.0),
    "in" to UnitDefinition(UnitFamily.LENGTH, 0.0254),
    "ft" to UnitDefinition(UnitFamily.LENGTH, 0.3048),
    "yd" to UnitDefinition(UnitFamily.LENGTH, 0.9144),
    "mi" to UnitDefinition(UnitFamily.LENGTH, 1_609.344),
    "mg" to UnitDefinition(UnitFamily.MASS, 0.000001),
    "g" to UnitDefinition(UnitFamily.MASS, 0.001),
    "kg" to UnitDefinition(UnitFamily.MASS, 1.0),
    "oz" to UnitDefinition(UnitFamily.MASS, 0.028349523125),
    "lb" to UnitDefinition(UnitFamily.MASS, 0.45359237),
    "ton_us" to UnitDefinition(UnitFamily.MASS, 907.18474),
    "ml" to UnitDefinition(UnitFamily.VOLUME, 0.001),
    "l" to UnitDefinition(UnitFamily.VOLUME, 1.0),
    "tsp" to UnitDefinition(UnitFamily.VOLUME, 0.00492892159375),
    "tbsp" to UnitDefinition(UnitFamily.VOLUME, 0.01478676478125),
    "cup" to UnitDefinition(UnitFamily.VOLUME, 0.2365882365),
    "pt" to UnitDefinition(UnitFamily.VOLUME, 0.473176473),
    "qt" to UnitDefinition(UnitFamily.VOLUME, 0.946352946),
    "gal" to UnitDefinition(UnitFamily.VOLUME, 3.785411784),
    "s" to UnitDefinition(UnitFamily.TIME, 1.0),
    "min" to UnitDefinition(UnitFamily.TIME, 60.0),
    "h" to UnitDefinition(UnitFamily.TIME, 3_600.0),
    "d" to UnitDefinition(UnitFamily.TIME, 86_400.0),
    "c" to UnitDefinition(UnitFamily.TEMPERATURE, 1.0),
    "f" to UnitDefinition(UnitFamily.TEMPERATURE, 1.0),
    "k" to UnitDefinition(UnitFamily.TEMPERATURE, 1.0),
)

private fun normalizeUnit(value: String): String {
    val raw = value.trim().lowercase(Locale.US).replace(" ", "_")
    return UNIT_ALIASES[raw] ?: raw
}

private fun convertUnits(value: Double, fromRaw: String, toRaw: String): Double {
    val fromName = normalizeUnit(fromRaw)
    val toName = normalizeUnit(toRaw)
    val from = UNITS[fromName] ?: throw ToolValidationException("Unsupported source unit: $fromRaw")
    val to = UNITS[toName] ?: throw ToolValidationException("Unsupported destination unit: $toRaw")
    if (from.family != to.family) throw ToolValidationException("Cannot convert $fromRaw to $toRaw because the units measure different quantities.")
    if (from.family == UnitFamily.TEMPERATURE) {
        val celsius = when (fromName) {
            "c" -> value
            "f" -> (value - 32.0) * 5.0 / 9.0
            "k" -> value - 273.15
            else -> value
        }
        return when (toName) {
            "c" -> celsius
            "f" -> celsius * 9.0 / 5.0 + 32.0
            "k" -> celsius + 273.15
            else -> celsius
        }
    }
    return value * from.toBase / to.toBase
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun formatNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private class ExpressionParser(private val input: String) {
    private var index = 0

    fun parse(): Double {
        val value = parseExpression()
        skipWhitespace()
        if (index != input.length) {
            throw ToolValidationException("Unexpected calculator input near '${input.substring(index).take(12)}'.")
        }
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (true) {
            skipWhitespace()
            value = when {
                consume('+') -> value + parseTerm()
                consume('-') -> value - parseTerm()
                else -> return value
            }
        }
    }

    private fun parseTerm(): Double {
        var value = parsePower()
        while (true) {
            skipWhitespace()
            value = when {
                consume('*') -> value * parsePower()
                consume('/') -> {
                    val divisor = parsePower()
                    if (divisor == 0.0) throw ToolValidationException("Division by zero is not allowed.")
                    value / divisor
                }
                consume('%') -> {
                    val divisor = parsePower()
                    if (divisor == 0.0) throw ToolValidationException("Modulo by zero is not allowed.")
                    value % divisor
                }
                else -> return value
            }
        }
    }

    private fun parsePower(): Double {
        var value = parseUnary()
        skipWhitespace()
        if (consume('^')) value = value.pow(parsePower())
        return value
    }

    private fun parseUnary(): Double {
        skipWhitespace()
        return when {
            consume('+') -> parseUnary()
            consume('-') -> -parseUnary()
            else -> parsePrimary()
        }
    }

    private fun parsePrimary(): Double {
        skipWhitespace()
        if (consume('(')) {
            val value = parseExpression()
            skipWhitespace()
            if (!consume(')')) throw ToolValidationException("A closing parenthesis is missing.")
            return value
        }
        return parseNumber()
    }

    private fun parseNumber(): Double {
        skipWhitespace()
        val start = index
        var sawDigit = false
        while (index < input.length && input[index].isDigit()) {
            sawDigit = true
            index++
        }
        if (index < input.length && input[index] == '.') {
            index++
            while (index < input.length && input[index].isDigit()) {
                sawDigit = true
                index++
            }
        }
        if (!sawDigit) throw ToolValidationException("A number was expected at position ${index + 1}.")
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            val exponentStart = index
            while (index < input.length && input[index].isDigit()) index++
            if (exponentStart == index) throw ToolValidationException("The scientific-notation exponent is incomplete.")
        }
        return input.substring(start, index).toDoubleOrNull()
            ?: throw ToolValidationException("Invalid number in calculator expression.")
    }

    private fun consume(expected: Char): Boolean {
        if (index < input.length && input[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index++
    }
}
