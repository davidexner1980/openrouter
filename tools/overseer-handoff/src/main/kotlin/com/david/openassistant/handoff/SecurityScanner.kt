package com.david.openassistant.handoff

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

enum class ScannerScope {
    SOURCE_CODE,
    TEST_SOURCE,
    DOCUMENTATION,
    RUNTIME_PROVIDER_DATA,
    GENERATED_EVIDENCE,
    LOG_OUTPUT
}

enum class MatchClassification {
    LIVE_SECRET_PROBABLE,
    SAFE_REDACTED_LITERAL,
    SAFE_TEST_FIXTURE,
    SAFE_DOCUMENTATION_EXAMPLE,
    FALSE_POSITIVE
}

class SecurityScanner {

    private val secretPatterns = listOf(
        // OpenRouter keys
        Pattern.compile("sk-or-v1-[a-zA-Z0-9]{64}"),
        // Generic Bearer tokens
        Pattern.compile("Bearer\\s+[a-zA-Z0-9._\\-]{20,256}"),
        // GitHub tokens
        Pattern.compile("gh[pousr]_[a-zA-Z0-9]{36}"),
        // AWS keys
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        // Basic Auth
        Pattern.compile("Authorization:\\s*Basic\\s+[a-zA-Z0-9+/=]{20,}"),
        // Cookies
        Pattern.compile("Set-Cookie:\\s*([^;]+)"),
        // Private keys (headers)
        Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----")
    )

    private val reasoningFields = setOf(
        "reasoning", "reasoning_details", "analysis", "internal_thought", "chain_of_thought"
    )

    data class ScanResult(
        val secretFindings: Int,
        val reasoningFieldsRemoved: Int,
        val unresolvedProbableSecrets: Int = 0,
        val absolutePathFindings: Int = 0
    )

    fun scan(text: String, scope: ScannerScope): ScanResult {
        var secretCount = 0
        var unresolvedCount = 0
        var absolutePathCount = 0
        
        for (pattern in secretPatterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val match = matcher.group()
                val classification = classifyMatch(match, text, matcher.start(), scope)
                secretCount++
                if (classification == MatchClassification.LIVE_SECRET_PROBABLE) {
                    unresolvedCount++
                }
            }
        }

        // OH-V12: Absolute path detection
        if (scope == ScannerScope.GENERATED_EVIDENCE || scope == ScannerScope.RUNTIME_PROVIDER_DATA || scope == ScannerScope.LOG_OUTPUT) {
            val pathPatterns = listOf(
                Pattern.compile("[A-Z]:\\\\[A-Za-z0-9._\\\\\\-\\s]+"), // Windows drive
                Pattern.compile("\\\\\\\\[A-Za-z0-9._\\\\\\-]+"), // UNC
                Pattern.compile("/(home|Users)/[A-Za-z0-9._\\-]+"), // Unix/Mac home
                Pattern.compile("/(?:gradle|studio|android-sdk)/[A-Za-z0-9._\\-/]+") // Cache/SDK
            )
            for (pattern in pathPatterns) {
                val matcher = pattern.matcher(text)
                while (matcher.find()) {
                    val match = matcher.group()
                    // Exclude common safe patterns
                    if (!isSafePath(match)) {
                        absolutePathCount++
                    }
                }
            }
        }

        return ScanResult(secretCount, 0, unresolvedCount, absolutePathCount)
    }

    private fun isSafePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("example") || lower.contains("test-fixture") || lower.contains("fake-path") ||
               lower.endsWith("acceptance-subject-manifest.json")
    }

    fun redact(text: String, scope: ScannerScope): Pair<String, ScanResult> {
        var currentText = text
        var secretCount = 0
        var reasoningCount = 0
        var unresolvedCount = 0
        var absolutePathCount = 0

        // 1. Secret Redaction
        for (pattern in secretPatterns) {
            val matcher = pattern.matcher(currentText)
            val sb = StringBuilder()
            var lastEnd = 0
            while (matcher.find()) {
                val match = matcher.group()
                val classification = classifyMatch(match, currentText, matcher.start(), scope)
                
                if (classification == MatchClassification.LIVE_SECRET_PROBABLE) {
                    unresolvedCount++
                }

                if (classification == MatchClassification.FALSE_POSITIVE || classification == MatchClassification.SAFE_REDACTED_LITERAL) {
                    sb.append(currentText, lastEnd, matcher.end())
                } else {
                    secretCount++
                    sb.append(currentText, lastEnd, matcher.start())
                    sb.append("[REDACTED_SECRET]")
                }
                lastEnd = matcher.end()
            }
            sb.append(currentText.substring(lastEnd))
            currentText = sb.toString()
        }

        // 2. Absolute Path Redaction
        if (scope == ScannerScope.GENERATED_EVIDENCE || scope == ScannerScope.RUNTIME_PROVIDER_DATA || scope == ScannerScope.LOG_OUTPUT) {
             val pathPatterns = listOf(
                Pattern.compile("[A-Z]:\\\\[A-Za-z0-9._\\\\\\-\\s]+"),
                Pattern.compile("\\\\\\\\[A-Za-z0-9._\\\\\\-]+"),
                Pattern.compile("/(home|Users)/[A-Za-z0-9._\\-]+")
            )
            for (pattern in pathPatterns) {
                val matcher = pattern.matcher(currentText)
                val sb = StringBuilder()
                var lastEnd = 0
                while (matcher.find()) {
                    val match = matcher.group()
                    if (!isSafePath(match)) {
                        absolutePathCount++
                        sb.append(currentText, lastEnd, matcher.start())
                        sb.append("[REDACTED_ABSOLUTE_PATH]")
                    } else {
                        sb.append(currentText, lastEnd, matcher.end())
                    }
                    lastEnd = matcher.end()
                }
                sb.append(currentText.substring(lastEnd))
                currentText = sb.toString()
            }
        }

        // 3. Hidden Reasoning Redaction
        if (scope == ScannerScope.RUNTIME_PROVIDER_DATA || scope == ScannerScope.GENERATED_EVIDENCE) {
            val (redactedText, count) = redactRecursive(currentText)
            currentText = redactedText
            reasoningCount += count
        }

        return Pair(currentText, ScanResult(secretCount, reasoningCount, unresolvedCount, absolutePathCount))
    }

    private fun classifyMatch(match: String, fullText: String, start: Int, scope: ScannerScope): MatchClassification {
        if (match.contains("[REDACTED]") || match.contains("[REDACTED_SECRET]")) return MatchClassification.SAFE_REDACTED_LITERAL
        
        if (match.lowercase().contains("password=\"false\"") || match.lowercase().contains("password=false")) {
            return MatchClassification.FALSE_POSITIVE
        }
        
        // Example placeholders
        if (match.contains("YOUR_API_KEY") || match.contains("example-token") || match.contains("sk-or-v1-abc")) {
            return MatchClassification.SAFE_DOCUMENTATION_EXAMPLE
        }

        // OH-018: Test source is NOT automatically safe.
        if (scope == ScannerScope.TEST_SOURCE) {
             // Safe only if it matches a known fake pattern
             if (match.contains("fake-") || match.contains("test-") || match.contains("00000000") || match.contains("supersecretvalue")) {
                 return MatchClassification.SAFE_TEST_FIXTURE
             }
             return MatchClassification.LIVE_SECRET_PROBABLE
        }
        
        // Regex patterns themselves (e.g. in this file)
        if (scope == ScannerScope.SOURCE_CODE) {
            val lineStart = fullText.lastIndexOf("\n", start).coerceAtLeast(0)
            val nextNewLine = fullText.indexOf("\n", start)
            val lineEnd = if (nextNewLine == -1) fullText.length else nextNewLine
            val line = fullText.substring(lineStart, lineEnd)
            
            // If it's inside Pattern.compile("...")
            if (line.contains("Pattern.compile")) {
                 return MatchClassification.FALSE_POSITIVE
            }
        }

        return MatchClassification.LIVE_SECRET_PROBABLE
    }

    private fun redactRecursive(text: String): Pair<String, Int> {
        var count = 0
        var resultText = text

        // Try JSON/JSONL
        try {
            if (text.trim().startsWith("{") || text.trim().startsWith("[")) {
                val (redacted, c) = redactJsonReasoning(text)
                if (c > 0) return Pair(redacted, c)
            }
        } catch (e: Exception) {}

        // JSONL
        if (text.contains("\n{")) {
            val lines = text.split("\n")
            val redactedLines = lines.map { line ->
                if (line.trim().startsWith("{")) {
                    val (redacted, c) = redactJsonReasoning(line)
                    count += c
                    redacted
                } else line
            }
            if (count > 0) return Pair(redactedLines.joinToString("\n"), count)
        }

        // SSE data: JSON
        if (text.contains("data: {")) {
            val ssePattern = Pattern.compile("data:\\s*(\\{.*?\\})", Pattern.DOTALL)
            val matcher = ssePattern.matcher(text)
            val sb = StringBuilder()
            var lastEnd = 0
            while (matcher.find()) {
                val jsonData = matcher.group(1)
                val (redacted, c) = redactJsonReasoning(jsonData)
                count += c
                sb.append(text, lastEnd, matcher.start(1))
                sb.append(redacted)
                lastEnd = matcher.end(1)
            }
            sb.append(text.substring(lastEnd))
            resultText = sb.toString()
        }

        // Markdown provider payload blocks
        if (resultText.contains("```json")) {
             val mdPattern = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL)
             val matcher = mdPattern.matcher(resultText)
             val sb = StringBuilder()
             var lastEnd = 0
             while (matcher.find()) {
                 val jsonData = matcher.group(1)
                 val (redacted, c) = redactJsonReasoning(jsonData)
                 count += c
                 sb.append(resultText, lastEnd, matcher.start(1))
                 sb.append(redacted)
                 lastEnd = matcher.end(1)
             }
             sb.append(resultText.substring(lastEnd))
             resultText = sb.toString()
        }

        return Pair(resultText, count)
    }

    private fun redactJsonReasoning(text: String): Pair<String, Int> {
        var count = 0
        try {
            if (text.trim().startsWith("{")) {
                val json = JSONObject(text)
                count = redactJsonObject(json)
                return Pair(json.toString(2), count)
            } else if (text.trim().startsWith("[")) {
                val array = JSONArray(text)
                count = redactJsonArray(array)
                return Pair(array.toString(2), count)
            }
        } catch (e: Exception) {}
        return Pair(text, 0)
    }

    private fun redactJsonObject(obj: JSONObject): Int {
        var count = 0
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            if (reasoningFields.contains(key.lowercase())) {
                obj.put(key, "[INTERNAL_REASONING_REDACTED]")
                count++
            } else {
                val value = obj.opt(key)
                if (value is JSONObject) {
                    count += redactJsonObject(value)
                } else if (value is JSONArray) {
                    count += redactJsonArray(value)
                }
            }
        }
        return count
    }

    private fun redactJsonArray(arr: JSONArray): Int {
        var count = 0
        for (i in 0 until arr.length()) {
            val value = arr.opt(i)
            if (value is JSONObject) {
                count += redactJsonObject(value)
            } else if (value is JSONArray) {
                count += redactJsonArray(value)
            }
        }
        return count
    }
}
