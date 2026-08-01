package com.david.openassistant.domain.tools

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val MAX_ADVANCED_TEXT_CHARS = 96_000
private const val MAX_ADVANCED_OUTPUT_CHARS = 96_000
private const val MAX_CSV_ROWS = 2_000
private const val MAX_CSV_COLUMNS = 100
private const val MAX_DIFF_LINES = 500
private const val MAX_JSON_DIFFS = 500
private const val MAX_URLS = 500
private const val MAX_WORD_FREQUENCY_ITEMS = 200

/**
 * Additional deterministic tools for the autonomous runtime. These tools only
 * transform caller-supplied values. They do not read device data, use Android
 * permissions, access the network, launch apps, or execute downloaded code.
 */
object AdvancedToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "percentage_change",
            displayName = "Percentage change",
            description = "Calculate absolute change, percentage change, and percentage-point change between two numbers.",
            parameters = listOf(
                ToolParameter("old_value", "Original numeric value."),
                ToolParameter("new_value", "New numeric value."),
            ),
        ),
        SafeToolDefinition(
            name = "date_add",
            displayName = "Date arithmetic",
            description = "Add or subtract days, weeks, months, or years from an ISO date.",
            parameters = listOf(
                ToolParameter("date", "Date in YYYY-MM-DD format."),
                ToolParameter("amount", "Signed whole-number amount."),
                ToolParameter("unit", "One of days, weeks, months, or years."),
            ),
        ),
        SafeToolDefinition(
            name = "business_days_between",
            displayName = "Business-day difference",
            description = "Count Monday-through-Friday days between two ISO dates. It does not infer public holidays.",
            parameters = listOf(
                ToolParameter("start_date", "Start date in YYYY-MM-DD format."),
                ToolParameter("end_date", "End date in YYYY-MM-DD format."),
                ToolParameter("include_end", "Optional true or false. Defaults to false.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "timezone_convert",
            displayName = "Time-zone converter",
            description = "Convert an ISO local date-time from one IANA time zone to another without using location or network access.",
            parameters = listOf(
                ToolParameter("datetime", "Local date-time such as 2026-07-16T14:30:00."),
                ToolParameter("from_timezone", "Source IANA time zone, such as America/Chicago."),
                ToolParameter("to_timezone", "Destination IANA time zone, such as Europe/London."),
            ),
        ),
        SafeToolDefinition(
            name = "extract_urls",
            displayName = "URL extractor",
            description = "Extract and deduplicate HTTP or HTTPS URLs from supplied text.",
            parameters = listOf(ToolParameter("text", "Text containing URLs.")),
        ),
        SafeToolDefinition(
            name = "word_frequency",
            displayName = "Word-frequency analyzer",
            description = "Count normalized words in supplied text and return the most frequent terms.",
            parameters = listOf(
                ToolParameter("text", "Text to analyze."),
                ToolParameter("limit", "Optional result count from 1 to 200. Defaults to 30.", required = false),
                ToolParameter("minimum_length", "Optional minimum word length from 1 to 30. Defaults to 2.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "text_diff",
            displayName = "Line diff",
            description = "Create a bounded unified-style line comparison between two supplied texts.",
            parameters = listOf(
                ToolParameter("before", "Original text."),
                ToolParameter("after", "Updated text."),
            ),
        ),
        SafeToolDefinition(
            name = "csv_summary",
            displayName = "CSV profiler",
            description = "Parse bounded CSV or delimiter-separated text and report row counts, columns, missing values, distinct counts, and numeric summaries.",
            parameters = listOf(
                ToolParameter("csv", "CSV text."),
                ToolParameter("delimiter", "Optional one-character delimiter. Defaults to comma.", required = false),
                ToolParameter("has_header", "Optional true or false. Defaults to true.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "csv_to_markdown",
            displayName = "CSV to Markdown table",
            description = "Convert bounded CSV text into a Markdown table.",
            parameters = listOf(
                ToolParameter("csv", "CSV text."),
                ToolParameter("delimiter", "Optional one-character delimiter. Defaults to comma.", required = false),
                ToolParameter("max_rows", "Optional maximum rows from 1 to 100. Defaults to 30.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "json_merge",
            displayName = "JSON merger",
            description = "Merge two supplied JSON objects. Nested objects are merged recursively; overlay values win.",
            parameters = listOf(
                ToolParameter("base_json", "Base JSON object."),
                ToolParameter("overlay_json", "Overlay JSON object."),
            ),
        ),
        SafeToolDefinition(
            name = "json_compare",
            displayName = "JSON structural comparison",
            description = "Compare two supplied JSON values and report changed, added, removed, or type-changed paths.",
            parameters = listOf(
                ToolParameter("left_json", "First JSON object or array."),
                ToolParameter("right_json", "Second JSON object or array."),
            ),
        ),
        SafeToolDefinition(
            name = "slugify",
            displayName = "Slug generator",
            description = "Convert supplied text into a lowercase ASCII URL/file slug.",
            parameters = listOf(ToolParameter("text", "Text to slugify.")),
        ),
        SafeToolDefinition(
            name = "checksum_compare",
            displayName = "Checksum comparison",
            description = "Calculate SHA-256 digests for two supplied texts and report whether they match.",
            parameters = listOf(
                ToolParameter("left", "First text."),
                ToolParameter("right", "Second text."),
            ),
        ),
        SafeToolDefinition(
            name = "html_escape",
            displayName = "HTML escaper",
            description = "Escape supplied text for safe literal inclusion in HTML text content.",
            parameters = listOf(ToolParameter("text", "Text to escape.")),
        ),
        SafeToolDefinition(
            name = "html_unescape",
            displayName = "HTML entity decoder",
            description = "Decode common named and numeric HTML entities in supplied text.",
            parameters = listOf(ToolParameter("text", "Text containing HTML entities.")),
        ),
        SafeToolDefinition(
            name = "list_compare",
            displayName = "List set comparison",
            description = "Compare two line- or delimiter-separated lists and report intersection and one-sided differences.",
            parameters = listOf(
                ToolParameter("left", "First list."),
                ToolParameter("right", "Second list."),
                ToolParameter("delimiter", "Optional delimiter. Defaults to new lines.", required = false),
                ToolParameter("case_sensitive", "Optional true or false. Defaults to false.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "source_url_audit",
            displayName = "Source URL audit",
            description = "Audit supplied source URLs for HTTPS use, duplicates, domain diversity, fragments, and query tracking parameters.",
            parameters = listOf(ToolParameter("urls", "URLs separated by new lines, commas, or JSON array text.")),
        ),
    )

    private val names = definitions.mapTo(mutableSetOf()) { it.name }
    fun handles(name: String): Boolean = name in names
}

class AdvancedToolExecutor {
    fun execute(call: OpenRouterToolCall): ToolExecutionResult {
        val arguments = parseArguments(call.argumentsJson)
        return when (call.name) {
            "percentage_change" -> percentageChange(arguments)
            "date_add" -> dateAdd(arguments)
            "business_days_between" -> businessDaysBetween(arguments)
            "timezone_convert" -> timezoneConvert(arguments)
            "extract_urls" -> extractUrls(arguments)
            "word_frequency" -> wordFrequency(arguments)
            "text_diff" -> textDiff(arguments)
            "csv_summary" -> csvSummary(arguments)
            "csv_to_markdown" -> csvToMarkdown(arguments)
            "json_merge" -> jsonMerge(arguments)
            "json_compare" -> jsonCompare(arguments)
            "slugify" -> slugify(arguments)
            "checksum_compare" -> checksumCompare(arguments)
            "html_escape" -> htmlEscape(arguments)
            "html_unescape" -> htmlUnescape(arguments)
            "list_compare" -> listCompare(arguments)
            "source_url_audit" -> sourceUrlAudit(arguments)
            else -> throw ToolValidationException("Unknown advanced tool: ${call.name}")
        }
    }

    private fun percentageChange(args: JSONObject): ToolExecutionResult {
        val old = requiredDouble(args, "old_value")
        val new = requiredDouble(args, "new_value")
        val delta = new - old
        val percent = if (old == 0.0) null else (delta / abs(old)) * 100.0
        val payload = JSONObject()
            .put("old_value", old)
            .put("new_value", new)
            .put("absolute_change", delta)
            .put("percentage_change", percent ?: JSONObject.NULL)
            .put("direction", when { delta > 0 -> "increase"; delta < 0 -> "decrease"; else -> "unchanged" })
        return ToolExecutionResult(
            payload.toString(),
            percent?.let { "Change: ${formatNumber(delta)} (${formatNumber(it)}%)." }
                ?: "Change: ${formatNumber(delta)}; percentage change is undefined because the original value is zero.",
        )
    }

    private fun dateAdd(args: JSONObject): ToolExecutionResult {
        val date = parseDate(requiredString(args, "date"), "date")
        val amount = requiredString(args, "amount").toLongOrNull()
            ?: throw ToolValidationException("amount must be a whole number.")
        if (!(amount in -1_000_000L..1_000_000L)) throw ToolValidationException("amount is outside the supported range.")
        val unit = requiredString(args, "unit").lowercase(Locale.US).trim().removeSuffix("s")
        val result = when (unit) {
            "day" -> date.plusDays(amount)
            "week" -> date.plusWeeks(amount)
            "month" -> date.plusMonths(amount)
            "year" -> date.plusYears(amount)
            else -> throw ToolValidationException("unit must be days, weeks, months, or years.")
        }
        return ToolExecutionResult(
            JSONObject().put("input_date", date.toString()).put("amount", amount).put("unit", unit).put("result_date", result.toString()).toString(),
            "$date ${if (amount >= 0) "+" else ""}$amount ${unit}s is $result.",
        )
    }

    private fun businessDaysBetween(args: JSONObject): ToolExecutionResult {
        val start = parseDate(requiredString(args, "start_date"), "start_date")
        val end = parseDate(requiredString(args, "end_date"), "end_date")
        val includeEnd = optionalBoolean(args, "include_end", default = false)
        val direction = if (end >= start) 1 else -1
        val terminal = if (includeEnd) end.plusDays(direction.toLong()) else end
        var cursor = start
        var count = 0L
        var guard = 0
        while (cursor != terminal) {
            if (cursor.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) count += direction
            cursor = cursor.plusDays(direction.toLong())
            guard++
            if (guard > 3_000_000) throw ToolValidationException("Date range is too large.")
        }
        return ToolExecutionResult(
            JSONObject().put("start_date", start.toString()).put("end_date", end.toString()).put("include_end", includeEnd).put("business_days", count).put("holidays_included", false).toString(),
            "$count weekday(s) between $start and $end${if (includeEnd) ", including the end date" else ""}.",
        )
    }

    private fun timezoneConvert(args: JSONObject): ToolExecutionResult {
        val raw = requiredString(args, "datetime")
        val local = runCatching { LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
            .getOrElse { throw ToolValidationException("datetime must be an ISO local date-time such as 2026-07-16T14:30:00.") }
        val from = parseZone(requiredString(args, "from_timezone"), "from_timezone")
        val to = parseZone(requiredString(args, "to_timezone"), "to_timezone")
        val source = ZonedDateTime.of(local, from)
        val target = source.withZoneSameInstant(to)
        return ToolExecutionResult(
            JSONObject()
                .put("source", source.format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
                .put("destination", target.format(DateTimeFormatter.ISO_ZONED_DATE_TIME))
                .put("instant", source.toInstant().toString())
                .toString(),
            "${source.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)} is ${target.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)}.",
        )
    }

    private fun extractUrls(args: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredStringAllowEmpty(args, "text"))
        val urls = URL_PATTERN.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?', '\'', '"') }
            .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            .distinct()
            .take(MAX_URLS)
            .toList()
        return ToolExecutionResult(
            JSONObject().put("count", urls.size).put("urls", JSONArray(urls)).toString(),
            "Extracted ${urls.size} unique URL(s).",
        )
    }

    private fun wordFrequency(args: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredStringAllowEmpty(args, "text"))
        val limit = optionalInt(args, "limit", 30).coerceIn(1, MAX_WORD_FREQUENCY_ITEMS)
        val minimumLength = optionalInt(args, "minimum_length", 2).coerceIn(1, 30)
        val counts = linkedMapOf<String, Int>()
        WORD_PATTERN.findAll(text.lowercase(Locale.US)).forEach { match ->
            val word = match.value.trim('_', '\'', '-')
            if (word.length >= minimumLength && word.any(Char::isLetterOrDigit)) {
                counts[word] = (counts[word] ?: 0) + 1
            }
        }
        val ranked = counts.entries
            .asSequence()
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .toList()
        val array = JSONArray().apply {
            ranked.forEach { put(JSONObject().put("word", it.key).put("count", it.value)) }
        }
        return ToolExecutionResult(
            JSONObject().put("unique_words", counts.size).put("returned", ranked.size).put("items", array).toString(),
            "Analyzed ${counts.values.sum()} word occurrence(s); returned ${ranked.size} frequent term(s).",
        )
    }

    private fun textDiff(args: JSONObject): ToolExecutionResult {
        val before = boundedText(requiredStringAllowEmpty(args, "before"))
        val after = boundedText(requiredStringAllowEmpty(args, "after"))
        val left = before.lines().take(MAX_DIFF_LINES)
        val right = after.lines().take(MAX_DIFF_LINES)
        val operations = lineDiff(left, right)
        val diff = buildString {
            appendLine("--- before")
            appendLine("+++ after")
            operations.forEach { (kind, line) ->
                append(kind)
                append(line)
                append('\n')
            }
            if (before.lines().size > MAX_DIFF_LINES || after.lines().size > MAX_DIFF_LINES) {
                appendLine("... diff truncated to $MAX_DIFF_LINES lines per side ...")
            }
        }.take(MAX_ADVANCED_OUTPUT_CHARS)
        val added = operations.count { it.first == '+' }
        val removed = operations.count { it.first == '-' }
        return ToolExecutionResult(
            JSONObject().put("added_lines", added).put("removed_lines", removed).put("diff", diff).toString(),
            "Line diff produced $added addition(s) and $removed removal(s).",
        )
    }

    private fun csvSummary(args: JSONObject): ToolExecutionResult {
        val csv = boundedText(requiredString(args, "csv"))
        val delimiter = delimiter(args)
        val hasHeader = optionalBoolean(args, "has_header", true)
        val rows = parseDelimited(csv, delimiter)
        if (rows.isEmpty()) throw ToolValidationException("CSV input contains no rows.")
        val width = rows.maxOf { it.size }.coerceAtMost(MAX_CSV_COLUMNS)
        val headers = if (hasHeader) {
            rows.first().take(width).mapIndexed { index, value -> value.ifBlank { "column_${index + 1}" } }
        } else {
            List(width) { "column_${it + 1}" }
        }
        val dataRows = (if (hasHeader) rows.drop(1) else rows).take(MAX_CSV_ROWS)
        val columns = JSONArray()
        for (columnIndex in 0 until width) {
            val values = dataRows.map { it.getOrElse(columnIndex) { "" } }
            val nonBlank = values.filter(String::isNotBlank)
            val numbers = nonBlank.mapNotNull { it.trim().toDoubleOrNull() }
            val column = JSONObject()
                .put("name", headers.getOrElse(columnIndex) { "column_${columnIndex + 1}" })
                .put("non_empty", nonBlank.size)
                .put("missing", values.size - nonBlank.size)
                .put("distinct", nonBlank.distinct().size)
                .put("numeric_count", numbers.size)
            if (numbers.isNotEmpty()) {
                column.put("minimum", numbers.minOrNull()).put("maximum", numbers.maxOrNull()).put("mean", numbers.average())
            }
            columns.put(column)
        }
        return ToolExecutionResult(
            JSONObject()
                .put("row_count", dataRows.size)
                .put("column_count", width)
                .put("truncated", (if (hasHeader) rows.size - 1 else rows.size) > MAX_CSV_ROWS)
                .put("columns", columns)
                .toString(),
            "Profiled ${dataRows.size} data row(s) across $width column(s).",
        )
    }

    private fun csvToMarkdown(args: JSONObject): ToolExecutionResult {
        val csv = boundedText(requiredString(args, "csv"))
        val delimiter = delimiter(args)
        val maxRows = optionalInt(args, "max_rows", 30).coerceIn(1, 100)
        val rows = parseDelimited(csv, delimiter)
        if (rows.isEmpty()) throw ToolValidationException("CSV input contains no rows.")
        val width = rows.maxOf { it.size }.coerceAtMost(MAX_CSV_COLUMNS)
        val normalized = rows.take(maxRows + 1).map { row -> List(width) { index -> row.getOrElse(index) { "" } } }
        val header = normalized.first()
        val body = normalized.drop(1).take(maxRows)
        fun cell(value: String) = value.replace("|", "\\|").replace("\n", " ").trim()
        val markdown = buildString {
            appendLine(header.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) })
            appendLine(List(width) { "---" }.joinToString(" | ", prefix = "| ", postfix = " |"))
            body.forEach { row -> appendLine(row.joinToString(" | ", prefix = "| ", postfix = " |") { cell(it) }) }
            if (rows.size > body.size + 1) appendLine("\n_${rows.size - body.size - 1} additional row(s) omitted._")
        }.take(MAX_ADVANCED_OUTPUT_CHARS)
        return ToolExecutionResult(
            JSONObject().put("rows_rendered", body.size).put("columns", width).put("markdown", markdown).toString(),
            "Converted ${body.size} row(s) and $width column(s) to Markdown.",
        )
    }

    private fun jsonMerge(args: JSONObject): ToolExecutionResult {
        val base = parseJsonObject(requiredString(args, "base_json"), "base_json")
        val overlay = parseJsonObject(requiredString(args, "overlay_json"), "overlay_json")
        val merged = deepMerge(JSONObject(base.toString()), overlay)
        val pretty = merged.toString(2).take(MAX_ADVANCED_OUTPUT_CHARS)
        return ToolExecutionResult(
            JSONObject().put("merged", merged).put("pretty", pretty).toString(),
            "Merged ${base.length()} base key(s) with ${overlay.length()} overlay key(s).",
        )
    }

    private fun jsonCompare(args: JSONObject): ToolExecutionResult {
        val left = parseJsonValue(requiredString(args, "left_json"), "left_json")
        val right = parseJsonValue(requiredString(args, "right_json"), "right_json")
        val diffs = mutableListOf<JSONObject>()
        compareJson(left, right, "$", diffs)
        val truncated = diffs.size > MAX_JSON_DIFFS
        val kept = diffs.take(MAX_JSON_DIFFS)
        return ToolExecutionResult(
            JSONObject().put("difference_count", diffs.size).put("truncated", truncated).put("differences", JSONArray(kept)).toString(),
            "Found ${diffs.size} JSON difference(s).",
        )
    }

    private fun slugify(args: JSONObject): ToolExecutionResult {
        val input = boundedText(requiredStringAllowEmpty(args, "text"))
        val slug = input
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(200)
        return ToolExecutionResult(JSONObject().put("slug", slug).toString(), "Generated slug '$slug'.")
    }

    private fun checksumCompare(args: JSONObject): ToolExecutionResult {
        val left = boundedText(requiredStringAllowEmpty(args, "left"))
        val right = boundedText(requiredStringAllowEmpty(args, "right"))
        val leftDigest = sha256(left)
        val rightDigest = sha256(right)
        val matches = leftDigest == rightDigest
        return ToolExecutionResult(
            JSONObject().put("algorithm", "SHA-256").put("left_digest", leftDigest).put("right_digest", rightDigest).put("matches", matches).toString(),
            if (matches) "The SHA-256 digests match." else "The SHA-256 digests do not match.",
        )
    }

    private fun htmlEscape(args: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredStringAllowEmpty(args, "text"))
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        return ToolExecutionResult(JSONObject().put("escaped", escaped.take(MAX_ADVANCED_OUTPUT_CHARS)).toString(), "Escaped ${text.length} character(s) for HTML text content.")
    }

    private fun htmlUnescape(args: JSONObject): ToolExecutionResult {
        val text = boundedText(requiredStringAllowEmpty(args, "text"))
        val numericDecoded = NUMERIC_ENTITY_PATTERN.replace(text) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", true)) token.drop(1).toIntOrNull(16) else token.toIntOrNull()
            codePoint?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: match.value
        }
        val decoded = numericDecoded
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        return ToolExecutionResult(JSONObject().put("decoded", decoded.take(MAX_ADVANCED_OUTPUT_CHARS)).toString(), "Decoded common HTML entities.")
    }

    private fun listCompare(args: JSONObject): ToolExecutionResult {
        val caseSensitive = optionalBoolean(args, "case_sensitive", default = false)
        val delimiter = optionalString(args, "delimiter")
        fun parse(value: String): List<String> {
            val raw = boundedText(value)
            val parts = if (delimiter.isNullOrEmpty()) raw.lines() else raw.split(delimiter)
            return parts.map(String::trim).filter(String::isNotEmpty).take(10_000)
        }
        val leftOriginal = parse(requiredStringAllowEmpty(args, "left"))
        val rightOriginal = parse(requiredStringAllowEmpty(args, "right"))
        fun key(value: String) = if (caseSensitive) value else value.lowercase(Locale.US)
        val left = leftOriginal.associateBy(::key)
        val right = rightOriginal.associateBy(::key)
        val intersection = left.keys.intersect(right.keys).sorted().mapNotNull(left::get)
        val onlyLeft = (left.keys - right.keys).sorted().mapNotNull(left::get)
        val onlyRight = (right.keys - left.keys).sorted().mapNotNull(right::get)
        return ToolExecutionResult(
            JSONObject()
                .put("intersection", JSONArray(intersection))
                .put("only_left", JSONArray(onlyLeft))
                .put("only_right", JSONArray(onlyRight))
                .put("intersection_count", intersection.size)
                .put("only_left_count", onlyLeft.size)
                .put("only_right_count", onlyRight.size)
                .toString(),
            "Lists share ${intersection.size} item(s); ${onlyLeft.size} only left and ${onlyRight.size} only right.",
        )
    }

    private fun sourceUrlAudit(args: JSONObject): ToolExecutionResult {
        val raw = boundedText(requiredString(args, "urls"))
        val candidates = runCatching {
            val array = JSONArray(raw)
            buildList { for (index in 0 until array.length()) add(array.optString(index)) }
        }.getOrElse { raw.split(Regex("[\\n,]+")) }
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_URLS)
        val normalized = candidates.map { it.trimEnd('.', ',', ';') }
        val duplicateCount = normalized.size - normalized.distinct().size
        val invalid = mutableListOf<String>()
        val nonHttps = mutableListOf<String>()
        val tracking = mutableListOf<String>()
        val domains = linkedSetOf<String>()
        normalized.distinct().forEach { value ->
            val uri = runCatching { URI(value) }.getOrNull()
            if (uri == null || uri.host.isNullOrBlank()) {
                invalid += value
            } else {
                domains += uri.host.lowercase(Locale.US).removePrefix("www.")
                if (!uri.scheme.equals("https", true)) nonHttps += value
                val query = uri.rawQuery.orEmpty().lowercase(Locale.US)
                if (TRACKING_PARAMETER_PATTERN.containsMatchIn(query)) tracking += value
            }
        }
        return ToolExecutionResult(
            JSONObject()
                .put("input_count", normalized.size)
                .put("unique_count", normalized.distinct().size)
                .put("duplicate_count", duplicateCount)
                .put("domain_count", domains.size)
                .put("domains", JSONArray(domains.toList().sorted()))
                .put("invalid_urls", JSONArray(invalid))
                .put("non_https_urls", JSONArray(nonHttps))
                .put("tracking_parameter_urls", JSONArray(tracking))
                .toString(),
            "Audited ${normalized.size} URL(s): ${domains.size} domain(s), $duplicateCount duplicate(s), ${invalid.size} invalid, ${nonHttps.size} non-HTTPS.",
        )
    }

    private fun lineDiff(left: List<String>, right: List<String>): List<Pair<Char, String>> {
        val lcs = Array(left.size + 1) { IntArray(right.size + 1) }
        for (i in left.indices.reversed()) {
            for (j in right.indices.reversed()) {
                lcs[i][j] = if (left[i] == right[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val result = mutableListOf<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < left.size || j < right.size) {
            when {
                i < left.size && j < right.size && left[i] == right[j] -> { result += ' ' to left[i]; i++; j++ }
                j < right.size && (i == left.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> { result += '+' to right[j]; j++ }
                i < left.size -> { result += '-' to left[i]; i++ }
            }
        }
        return result
    }

    private fun deepMerge(base: JSONObject, overlay: JSONObject): JSONObject {
        val keys = overlay.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val overlayValue = overlay.opt(key)
            val baseValue = base.opt(key)
            if (overlayValue is JSONObject && baseValue is JSONObject) {
                base.put(key, deepMerge(JSONObject(baseValue.toString()), overlayValue))
            } else {
                base.put(key, overlayValue)
            }
        }
        return base
    }

    private fun compareJson(left: Any?, right: Any?, path: String, diffs: MutableList<JSONObject>) {
        if (diffs.size > MAX_JSON_DIFFS) return
        when {
            left is JSONObject && right is JSONObject -> {
                val keys = linkedSetOf<String>()
                val leftKeys = left.keys()
                while (leftKeys.hasNext()) keys += leftKeys.next()
                val rightKeys = right.keys()
                while (rightKeys.hasNext()) keys += rightKeys.next()
                keys.sorted().forEach { key ->
                    if (diffs.size > MAX_JSON_DIFFS) return@forEach
                    val leftHas = left.has(key)
                    val rightHas = right.has(key)
                    when {
                        !leftHas -> diffs += diff(pathFor(path, key), "added", null, right.opt(key))
                        !rightHas -> diffs += diff(pathFor(path, key), "removed", left.opt(key), null)
                        else -> compareJson(left.opt(key), right.opt(key), pathFor(path, key), diffs)
                    }
                }
            }
            left is JSONArray && right is JSONArray -> {
                val count = maxOf(left.length(), right.length())
                for (index in 0 until count) {
                    if (diffs.size > MAX_JSON_DIFFS) break
                    when {
                        index >= left.length() -> diffs += diff("$path[$index]", "added", null, right.opt(index))
                        index >= right.length() -> diffs += diff("$path[$index]", "removed", left.opt(index), null)
                        else -> compareJson(left.opt(index), right.opt(index), "$path[$index]", diffs)
                    }
                }
            }
            jsonType(left) != jsonType(right) -> diffs += diff(path, "type_changed", left, right)
            !jsonEquivalent(left, right) -> diffs += diff(path, "changed", left, right)
        }
    }

    private fun diff(path: String, type: String, left: Any?, right: Any?): JSONObject = JSONObject()
        .put("path", path)
        .put("type", type)
        .put("left", left ?: JSONObject.NULL)
        .put("right", right ?: JSONObject.NULL)

    private fun pathFor(parent: String, key: String): String = if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) "$parent.$key" else "$parent['${key.replace("'", "\\'")}']"

    private fun jsonType(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> "string"
    }

    private fun jsonEquivalent(left: Any?, right: Any?): Boolean = when (left) {
        JSONObject.NULL -> right == JSONObject.NULL
        is Number -> (right is Number) && (left.toDouble() == right.toDouble())
        else -> left == right || left?.toString() == right?.toString()
    }

    private fun parseDelimited(raw: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < raw.length && rows.size <= MAX_CSV_ROWS + 2) {
            val ch = raw[index]
            when {
                ch == '"' && inQuotes && index + 1 < raw.length && raw[index + 1] == '"' -> { cell.append('"'); index++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> { row += cell.toString(); cell.setLength(0) }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (ch == '\r' && index + 1 < raw.length && raw[index + 1] == '\n') index++
                    row += cell.toString(); cell.setLength(0)
                    if (row.any(String::isNotEmpty)) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (inQuotes) throw ToolValidationException("CSV contains an unclosed quoted field.")
        row += cell.toString()
        if (row.any(String::isNotEmpty)) rows += row
        if (rows.any { it.size > MAX_CSV_COLUMNS }) throw ToolValidationException("CSV is limited to $MAX_CSV_COLUMNS columns.")
        return rows
    }

    private fun delimiter(args: JSONObject): Char {
        val raw = optionalString(args, "delimiter") ?: ","
        if (raw.length != 1) throw ToolValidationException("delimiter must contain exactly one character.")
        return raw[0]
    }

    private fun parseJsonValue(raw: String, field: String): Any = try {
        val trimmed = raw.trim()
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> throw ToolValidationException("$field must contain a JSON object or array.")
        }
    } catch (error: ToolValidationException) {
        throw error
    } catch (_: Exception) {
        throw ToolValidationException("$field contains invalid JSON.")
    }

    private fun parseJsonObject(raw: String, field: String): JSONObject = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw ToolValidationException("$field must contain a valid JSON object.")
    }

    private fun parseArguments(raw: String): JSONObject = parseToolArguments(raw)

    private fun requiredString(args: JSONObject, name: String): String {
        val value = args.optString(name).trim()
        if (value.isBlank() || value == "null") throw ToolValidationException("Missing required tool argument: $name.")
        return value
    }

    private fun requiredStringAllowEmpty(args: JSONObject, name: String): String {
        if (!args.has(name) || args.isNull(name)) throw ToolValidationException("Missing required tool argument: $name.")
        return args.optString(name)
    }

    private fun optionalString(args: JSONObject, name: String): String? = args.optString(name)
        .trim()
        .takeIf { it.isNotBlank() && it != "null" }

    private fun requiredDouble(args: JSONObject, name: String): Double = requiredString(args, name).toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?: throw ToolValidationException("$name must be a finite number.")

    private fun optionalBoolean(args: JSONObject, name: String, default: Boolean): Boolean {
        val raw = optionalString(args, name) ?: return default
        return raw.toBooleanStrictOrNull() ?: throw ToolValidationException("$name must be true or false.")
    }

    private fun optionalInt(args: JSONObject, name: String, default: Int): Int = optionalString(args, name)?.toIntOrNull() ?: default

    private fun boundedText(value: String): String {
        if (value.length > MAX_ADVANCED_TEXT_CHARS) throw ToolValidationException("Advanced tool text is limited to $MAX_ADVANCED_TEXT_CHARS characters.")
        return value
    }

    private fun parseDate(value: String, field: String): LocalDate = runCatching { LocalDate.parse(value) }
        .getOrElse { throw ToolValidationException("$field must use YYYY-MM-DD format.") }

    private fun parseZone(value: String, field: String): ZoneId = runCatching { ZoneId.of(value) }
        .getOrElse { throw ToolValidationException("$field is not a valid IANA time zone.") }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun formatNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        private val URL_PATTERN = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
        private val WORD_PATTERN = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}_'’-]*")
        private val NUMERIC_ENTITY_PATTERN = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")
        private val TRACKING_PARAMETER_PATTERN = Regex("(^|&)(utm_[a-z_]+|gclid|fbclid|mc_cid|mc_eid)=")
    }
}
