package com.david.openassistant

import com.david.openassistant.domain.tools.AdvancedToolCatalog
import com.david.openassistant.domain.tools.AdvancedToolExecutor
import com.david.openassistant.domain.tools.OpenRouterToolCall
import com.david.openassistant.domain.tools.SafeToolCatalog
import com.david.openassistant.domain.tools.SafeToolExecutor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes every built-in that is independent of Android state, network, or a
 * stored provider credential. The connected-device suite certifies the other
 * fourteen tools, giving the release a real 49/49 execution contract.
 */
class BuiltInToolCertificationTest {
    @Test
    fun everyPureBuiltInExecutesARepresentativeCall() {
        val cases = certificationCases()
        val expectedNames = (
            SafeToolCatalog.definitions
                .map { it.name }
                .filterNot { it in FOUNDRY_TOOL_NAMES } +
                AdvancedToolCatalog.definitions.map { it.name }
            ).toSet()

        assertEquals(35, cases.size)
        assertEquals(expectedNames, cases.map { it.name }.toSet())

        cases.forEachIndexed { index, case ->
            val call = OpenRouterToolCall(
                id = "certification-$index",
                name = case.name,
                argumentsJson = case.arguments.toString(),
            )
            val result = if (AdvancedToolCatalog.handles(case.name)) {
                AdvancedToolExecutor().execute(call)
            } else {
                SafeToolExecutor().execute(call)
            }
            val payload = JSONObject(result.outputJson)

            assertTrue("${case.name} returned no JSON fields", payload.length() > 0)
            assertTrue("${case.name} omitted ${case.expectedKey}", payload.has(case.expectedKey))
            assertTrue("${case.name} returned no readable summary", result.displaySummary.isNotBlank())
            assertFalse("${case.name} leaked a non-finite number", result.outputJson.contains("NaN"))
        }
    }

    @Test
    fun allStaticToolSchemasAreUniqueCompleteAndProviderSafe() {
        val definitions = SafeToolCatalog.definitions + AdvancedToolCatalog.definitions
        val casesByName = certificationCases().associateBy { it.name }

        assertEquals(definitions.size, definitions.map { it.name }.distinct().size)
        definitions.forEach { definition ->
            assertTrue("Invalid function name ${definition.name}", FUNCTION_NAME.matches(definition.name))
            assertTrue("${definition.name} has no display name", definition.displayName.isNotBlank())
            assertTrue("${definition.name} has no description", definition.description.isNotBlank())
            assertEquals(definition.parameters.size, definition.parameters.map { it.name }.distinct().size)
            definition.parameters.forEach { parameter ->
                assertTrue("${definition.name}.${parameter.name} has no description", parameter.description.isNotBlank())
                assertTrue("${definition.name}.${parameter.name} uses unsupported type", parameter.type in SUPPORTED_TYPES)
            }
            if (definition.name !in FOUNDRY_TOOL_NAMES) {
                val case = casesByName.getValue(definition.name)
                definition.parameters.filter { it.required }.forEach { parameter ->
                    assertTrue(
                        "Certification input for ${definition.name} omits required ${parameter.name}",
                        case.arguments.has(parameter.name),
                    )
                }
            }
        }
    }

    private fun certificationCases(): List<ToolCase> = listOf(
        tool("calculate", "result", "expression" to "(4 + 9) * 2"),
        tool("current_date_time", "iso", "timezone" to "America/Chicago"),
        tool("date_difference", "days", "start_date" to "2026-07-01", "end_date" to "2026-07-17"),
        tool("unit_convert", "result", "value" to "10", "from_unit" to "ft", "to_unit" to "m"),
        tool("statistics", "mean", "numbers" to "1, 2, 3, 4"),
        tool("count_text", "words", "text" to "one two\nthree"),
        tool("find_text", "matches", "text" to "Alpha beta alpha", "query" to "alpha"),
        tool("extract_regex", "matches", "text" to "part-123", "pattern" to "part-([0-9]{3})", "group" to "1"),
        tool("replace_regex", "output", "text" to "A  B", "pattern" to "\\s+", "replacement" to "_"),
        tool("sort_unique_lines", "output", "text" to "beta\nalpha\nbeta"),
        tool("format_json", "formatted", "json" to "{\"b\":2,\"a\":1}", "indent" to "2"),
        tool("json_get", "value", "json" to "{\"items\":[{\"name\":\"alpha\"}]}", "path" to "items.0.name"),
        tool("sha256_text", "sha256", "text" to "certification"),
        tool("base64_encode", "base64", "text" to "hello"),
        tool("base64_decode", "text", "base64" to "aGVsbG8="),
        tool("url_encode", "encoded", "text" to "hello world"),
        tool("url_decode", "decoded", "text" to "hello%20world"),
        tool("generate_uuid", "uuids", "count" to "2"),
        tool("percentage_change", "percentage_change", "old_value" to "100", "new_value" to "125"),
        tool("date_add", "result_date", "date" to "2026-01-31", "amount" to "1", "unit" to "months"),
        tool("business_days_between", "business_days", "start_date" to "2026-07-13", "end_date" to "2026-07-17", "include_end" to "true"),
        tool("timezone_convert", "destination", "datetime" to "2026-07-17T14:30:00", "from_timezone" to "America/Chicago", "to_timezone" to "Europe/London"),
        tool("extract_urls", "urls", "text" to "See https://example.com/a and https://example.org/b."),
        tool("word_frequency", "items", "text" to "evidence source evidence method source evidence", "limit" to "5"),
        tool("text_diff", "diff", "before" to "alpha\nbeta", "after" to "alpha\ngamma"),
        tool("csv_summary", "columns", "csv" to "name,value\nalpha,1\nbeta,2", "has_header" to "true"),
        tool("csv_to_markdown", "markdown", "csv" to "name,value\nalpha,1\nbeta,2"),
        tool("json_merge", "merged", "base_json" to "{\"a\":1,\"nested\":{\"x\":1}}", "overlay_json" to "{\"b\":2,\"nested\":{\"y\":2}}"),
        tool("json_compare", "differences", "left_json" to "{\"a\":1}", "right_json" to "{\"a\":2,\"b\":3}"),
        tool("slugify", "slug", "text" to "Deep Research: Evidence & Methods"),
        tool("checksum_compare", "matches", "left" to "same", "right" to "same"),
        tool("html_escape", "escaped", "text" to "<strong>A&B</strong>"),
        tool("html_unescape", "decoded", "text" to "&lt;strong&gt;A&amp;B&lt;/strong&gt;"),
        tool("list_compare", "intersection", "left" to "alpha\nbeta", "right" to "beta\ngamma"),
        tool("source_url_audit", "domains", "urls" to "https://example.com/a\nhttp://example.org/b?utm_source=test"),
    )

    private fun tool(name: String, expectedKey: String, vararg arguments: Pair<String, Any>): ToolCase =
        ToolCase(
            name = name,
            expectedKey = expectedKey,
            arguments = JSONObject().apply { arguments.forEach { (key, value) -> put(key, value) } },
        )

    private data class ToolCase(
        val name: String,
        val expectedKey: String,
        val arguments: JSONObject,
    )

    private companion object {
        val FOUNDRY_TOOL_NAMES = setOf("create_tool_recipe", "list_tool_recipes", "disable_tool_recipe")
        val FUNCTION_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        val SUPPORTED_TYPES = setOf("string", "number", "integer", "boolean", "object", "array")
    }
}
