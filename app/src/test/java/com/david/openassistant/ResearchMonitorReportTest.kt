package com.david.openassistant

import com.david.openassistant.data.diagnostics.ResearchMonitorReportMetadata
import com.david.openassistant.data.diagnostics.ResearchMonitorReportWriter
import com.david.openassistant.data.diagnostics.redactResearchMonitorText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ResearchMonitorReportTest {
    @Test
    fun redaction_preserves_research_content_but_removes_credentials_and_binary_data() {
        val firstFakeSecret = "sk-or-v1-" + "fake-supersecretvalue"
        val secondFakeSecret = "sk-or-v1-" + "fake-anothersecretvalue"
        val raw = """
            {"authorization":"Bearer $firstFakeSecret","prompt":"Follow the newly discovered archival lead","image":"data:image/jpeg;base64,QUJDREVGRw=="}
            api_key=$secondFakeSecret
            Set-Cookie: provider_session=fake-secret-cookie-value; Secure; HttpOnly
            {"id_token":"fake-private-identity-token","client_secret":"fake-private-client-secret","storefront_access_token":"fake-public-page-token-that-must-still-be-redacted"}
            vendor_refresh_token=fake-query-secret
        """.trimIndent()

        val redacted = redactResearchMonitorText(raw)

        assertTrue(redacted.contains("Follow the newly discovered archival lead"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("[BINARY DATA URL REDACTED"))
        assertFalse(redacted.contains("supersecretvalue"))
        assertFalse(redacted.contains("anothersecretvalue"))
        assertFalse(redacted.contains("secret-cookie-value"))
        assertFalse(redacted.contains("private-identity-token"))
        assertFalse(redacted.contains("private-client-secret"))
        assertFalse(redacted.contains("public-page-token-that-must-still-be-redacted"))
        assertFalse(redacted.contains("query-secret"))
        assertFalse(redacted.contains("QUJDREVGRw=="))
    }

    @Test
    fun report_indexes_failures_provider_exchanges_searches_and_complete_payloads() {
        val directory = Files.createTempDirectory("openassistant-monitor-test").toFile()
        val trace = directory.resolve("session.jsonl")
        val report = directory.resolve("report.md")
        val events = listOf(
            event("provider", "request", correlation = "provider-1", details = mapOf("request_body" to "Investigate an unfamiliar boundary-datum question")),
            event("provider", "response", correlation = "provider-1", details = mapOf("response_body" to "A request-specific research strategy")),
            event("tool", "call_started", correlation = "tool-1", details = mapOf("tool_name" to "public_web_search", "arguments_json" to "{\"query\":\"exact unfamiliar query\"}")),
            event("tool", "call_completed", correlation = "tool-1", details = mapOf("tool_name" to "public_web_search", "output_json" to "{\"sources\":[{\"url\":\"https://example.org/source\"}]}")),
            event("runtime", "agent_milestone_failed", level = "ERROR", details = mapOf("error_message" to "The contradiction evidence was incomplete.")),
        )
        trace.writeText(events.joinToString("\n") + "\n", StandardCharsets.UTF_8)

        ResearchMonitorReportWriter.write(
            traceFile = trace,
            reportFile = report,
            metadata = ResearchMonitorReportMetadata(
                sessionId = "test-session",
                startedAt = 1_000L,
                finishedAt = 2_000L,
                appVersion = "test",
                device = "test device",
                androidVersion = "test Android",
                monitoringStillActive = false,
                traceWasCapped = false,
            ),
        )

        val markdown = report.readText(StandardCharsets.UTF_8)
        assertTrue(markdown.contains("Total events: 5"))
        assertTrue(markdown.contains("OpenRouter requests: 1"))
        assertTrue(markdown.contains("Unfinished OpenRouter requests at report boundary: 0"))
        assertTrue(markdown.contains("Tool calls started: 1"))
        assertTrue(markdown.contains("Public-web searches: 1"))
        assertTrue(markdown.contains("The contradiction evidence was incomplete."))
        assertTrue(markdown.contains("[COMPACTED:"))
        assertTrue(markdown.contains("exact unfamiliar query"))
        assertTrue(markdown.contains("https://example.org/source"))
        assertEquals(5, Regex("^### ", RegexOption.MULTILINE).findAll(markdown).count())
    }

    @Test
    fun report_flags_an_unmatched_provider_request_as_unfinished_work() {
        val directory = Files.createTempDirectory("openassistant-unfinished-provider-test").toFile()
        val trace = directory.resolve("session.jsonl")
        val report = directory.resolve("report.md")
        trace.writeText(
            listOf(
                event(
                    "provider",
                    "request",
                    correlation = "provider-still-running",
                    details = mapOf(
                        "operation" to "agent_structured_chat",
                        "endpoint" to "https://openrouter.ai/api/v1/chat/completions",
                    ),
                ),
                event(
                    "monitor",
                    "final_report_requested",
                    details = mapOf("session_will_stop_after_successful_report" to true),
                ),
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8,
        )

        ResearchMonitorReportWriter.write(
            traceFile = trace,
            reportFile = report,
            metadata = ResearchMonitorReportMetadata(
                sessionId = "unfinished-session",
                startedAt = 1_000L,
                finishedAt = 2_000L,
                appVersion = "test",
                device = "test device",
                androidVersion = "test Android",
                monitoringStillActive = false,
                traceWasCapped = false,
                reportKind = "final",
            ),
        )

        val markdown = report.readText(StandardCharsets.UTF_8)
        assertTrue(markdown.contains("Unfinished OpenRouter requests at report boundary: 1"))
        assertTrue(markdown.contains("request_unfinished_at_report_boundary"))
        assertTrue(markdown.contains("This is unfinished work, not a successful provider outcome."))
        assertFalse(markdown.contains("No error, warning, cancellation, timeout, or recovery event was recorded."))
    }

    @Test
    fun unrelated_provider_outcome_does_not_hide_an_unfinished_request() {
        val directory = Files.createTempDirectory("openassistant-provider-correlation-test").toFile()
        val trace = directory.resolve("session.jsonl")
        val report = directory.resolve("report.md")
        trace.writeText(
            listOf(
                event("provider", "request", correlation = "still-pending", details = emptyMap()),
                event("provider", "response", correlation = "different-exchange", details = emptyMap()),
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8,
        )

        ResearchMonitorReportWriter.write(
            traceFile = trace,
            reportFile = report,
            metadata = ResearchMonitorReportMetadata(
                sessionId = "correlation-session",
                startedAt = 1_000L,
                finishedAt = 2_000L,
                appVersion = "test",
                device = "test device",
                androidVersion = "test Android",
                monitoringStillActive = false,
                traceWasCapped = false,
            ),
        )

        val markdown = report.readText(StandardCharsets.UTF_8)
        assertTrue(markdown.contains("OpenRouter requests: 1"))
        assertTrue(markdown.contains("OpenRouter terminal outcomes: 0"))
        assertTrue(markdown.contains("Unfinished OpenRouter requests at report boundary: 1"))
    }

    @Test
    fun duplicate_and_orphan_terminal_events_do_not_overcount_opened_requests() {
        val directory = Files.createTempDirectory("openassistant-provider-terminal-dedup-test").toFile()
        val trace = directory.resolve("session.jsonl")
        val report = directory.resolve("report.md")
        trace.writeText(
            listOf(
                event("provider", "request", correlation = "exchange-1", details = emptyMap()),
                event("provider", "response", correlation = "exchange-1", details = emptyMap()),
                event("runtime", "exchange_terminal_updated", details = mapOf("exchangeId" to "exchange-1", "outcome" to "SUCCESS")),
                event("provider", "failure", correlation = "orphan-exchange", details = emptyMap()),
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8,
        )

        ResearchMonitorReportWriter.write(
            traceFile = trace,
            reportFile = report,
            metadata = ResearchMonitorReportMetadata(
                sessionId = "terminal-dedup-session",
                startedAt = 1_000L,
                finishedAt = 2_000L,
                appVersion = "test",
                device = "test device",
                androidVersion = "test Android",
                monitoringStillActive = false,
                traceWasCapped = false,
            ),
        )

        val markdown = report.readText(StandardCharsets.UTF_8)
        assertTrue(markdown.contains("OpenRouter requests: 1"))
        assertTrue(markdown.contains("OpenRouter terminal outcomes: 1"))
        assertTrue(markdown.contains("Unfinished OpenRouter requests at report boundary: 0"))
    }

    @Test
    fun runtime_terminal_exchange_event_settles_a_provider_request_even_without_duplicate_provider_failure() {
        val directory = Files.createTempDirectory("openassistant-runtime-terminal-test").toFile()
        val trace = directory.resolve("session.jsonl")
        val report = directory.resolve("report.md")
        trace.writeText(
            listOf(
                event("provider", "request", correlation = "exchange-1", details = mapOf("operation" to "agent_structured_chat")),
                event("runtime", "exchange_terminal_updated", details = mapOf("exchangeId" to "exchange-1", "outcome" to "TRANSPORT_FAILURE")),
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8,
        )

        ResearchMonitorReportWriter.write(
            traceFile = trace,
            reportFile = report,
            metadata = ResearchMonitorReportMetadata(
                sessionId = "runtime-terminal-session",
                startedAt = 1_000L,
                finishedAt = 2_000L,
                appVersion = "test",
                device = "test device",
                androidVersion = "test Android",
                monitoringStillActive = false,
                traceWasCapped = false,
            ),
        )

        val markdown = report.readText(StandardCharsets.UTF_8)
        assertTrue(markdown.contains("OpenRouter requests: 1"))
        assertTrue(markdown.contains("OpenRouter terminal outcomes: 1"))
        assertTrue(markdown.contains("Unfinished OpenRouter requests at report boundary: 0"))
    }

    @Test
    fun final_cancel_report_atomically_replaces_stale_output_and_preserves_cancel_boundary() {
        val directory = Files.createTempDirectory("openassistant-cancel-report-test").toFile()
        val trace = directory.resolve("session.jsonl")
        val report = directory.resolve("report.md").apply { writeText("stale partial report") }
        trace.writeText(
            listOf(
                event(
                    "user_action",
                    "mission_cancel_requested",
                    level = "WARN",
                    correlation = "goal-1",
                    details = mapOf("reason" to "Possible stalled provider call"),
                ),
                event(
                    "mission",
                    "worker_cancel_signal_received",
                    level = "WARN",
                    correlation = "goal-1",
                    details = mapOf("provider_calls_cancelled" to true),
                ),
                event(
                    "mission",
                    "cancel_boundary_reached",
                    level = "WARN",
                    correlation = "goal-1",
                    details = mapOf("worker_cancellation_waited" to true),
                ),
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8,
        )

        ResearchMonitorReportWriter.write(
            traceFile = trace,
            reportFile = report,
            metadata = ResearchMonitorReportMetadata(
                sessionId = "cancel-session",
                startedAt = 1_000L,
                finishedAt = 2_000L,
                appVersion = "test",
                device = "test device",
                androidVersion = "test Android",
                monitoringStillActive = false,
                traceWasCapped = false,
            ),
        )

        val markdown = report.readText(StandardCharsets.UTF_8)
        assertFalse(markdown.contains("stale partial report"))
        assertTrue(markdown.contains("mission_cancel_requested"))
        assertTrue(markdown.contains("worker_cancel_signal_received"))
        assertTrue(markdown.contains("cancel_boundary_reached"))
        assertTrue(markdown.contains("Monitor still active after this snapshot: false"))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun redaction_excludes_reasoning_fields_from_json_blobs() {
        val raw = """
            {"model":"test-model","reasoning":"Thinking about the answer...","details":{"reasoning_details":"Step-by-step logic","result":"The final answer"}}
        """.trimIndent()

        val redacted = redactResearchMonitorText(raw)

        assertTrue(redacted.contains("The final answer"))
        assertTrue(redacted.contains("[EXCLUDED FROM MONITOR]"))
        assertFalse(redacted.contains("Thinking about the answer"))
        assertFalse(redacted.contains("Step-by-step logic"))
    }

    private fun event(
        category: String,
        name: String,
        level: String = "INFO",
        correlation: String? = null,
        details: Map<String, Any?>,
    ): String = JSONObject()
        .put("timestamp_ms", 1_500L)
        .put("event_id", "$category-$name")
        .put("session_id", "test-session")
        .put("level", level)
        .put("category", category)
        .put("event", name)
        .put("details", JSONObject(details))
        .apply { correlation?.let { put("correlation_id", it) } }
        .toString()
}
