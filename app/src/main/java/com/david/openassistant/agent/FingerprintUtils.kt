package com.david.openassistant.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned SHA-256 canonicalization for agent state dimensions.
 * Cosmetic changes (whitespace, casing, punctuation-only) are normalized
 * to ensure that only material changes produce a new fingerprint.
 */
object FingerprintUtils {
    private const val FINGERPRINT_VERSION = "V42_1"

    fun computeRootObjectiveFingerprint(goal: AgentGoal): String {
        return canonicalHash(
            "root_objective",
            mapOf(
                "request" to goal.userRequest,
                "constraints" to goal.confirmedConstraints.sorted(),
                "exclusions" to goal.exclusions.sorted(),
                "grounded" to goal.groundedConstraints.map { it.text }.sorted()
            )
        )
    }

    fun computeOperationalObjectiveFingerprint(revision: ObjectiveRevision): String {
        return canonicalHash(
            "operational_objective",
            mapOf(
                "version" to revision.ordinal,
                "objective" to revision.operationalObjective,
                "gaps" to revision.unresolvedGaps.sorted(),
                "constraints" to revision.retainedConstraints.sorted()
            )
        )
    }

    fun computeAcceptedEvidenceFingerprint(evidence: List<AgentEvidence>): String {
        val sortedIds = evidence
            .filter { it.kind != AgentEvidenceKind.SYSTEM_EVENT }
            .map { it.id }
            .sorted()
        return canonicalHash("accepted_evidence", mapOf("ids" to sortedIds))
    }

    fun computeUnresolvedGapFingerprint(gaps: List<String>): String {
        return canonicalHash("unresolved_gaps", mapOf("gaps" to gaps.sorted()))
    }

    fun computeStrategyFingerprint(strategyJson: String?): String {
        if (strategyJson.isNullOrBlank()) return "none"
        val normalized = runCatching {
            val json = JSONObject(strategyJson)
            canonicalizeJson(json)
        }.getOrDefault(normalizeText(strategyJson))
        return canonicalHash("strategy", mapOf("json" to normalized))
    }

    fun computeQueryPortfolioFingerprint(queries: List<String>): String {
        return canonicalHash("query_portfolio", mapOf("queries" to queries.map { normalizeText(it) }.sorted()))
    }

    fun computeExecutionContextFingerprint(
        goal: AgentGoal,
        task: AgentTask,
        activeCycle: ResearchCycle?
    ): String {
        return canonicalHash(
            "execution_context",
            mapOf(
                "goal_id" to goal.id,
                "task_id" to task.id,
                "cycle_id" to (activeCycle?.id ?: "baseline"),
                "root_obj" to computeRootObjectiveFingerprint(goal),
                "op_obj" to (activeCycle?.objectiveRevisionId ?: "root"),
                "evidence" to computeAcceptedEvidenceFingerprint(goal.evidence),
                "gaps" to computeUnresolvedGapFingerprint(task.acceptanceCriteria.map { it.description })
            )
        )
    }

    private fun canonicalHash(dimension: String, data: Any): String {
        val canonical = when (data) {
            is Map<*, *> -> canonicalizeMap(data)
            is List<*> -> canonicalizeList(data)
            else -> normalizeText(data.toString())
        }
        val input = "$FINGERPRINT_VERSION:$dimension:$canonical"
        return sha256(input)
    }

    private fun canonicalizeMap(map: Map<*, *>): String {
        return map.entries
            .asSequence()
            .map { (k, v) -> normalizeText(k.toString()) to v }
            .sortedBy { it.first }
            .joinToString(",") { (k, v) ->
                val valStr = when (v) {
                    is List<*> -> canonicalizeList(v)
                    is Map<*, *> -> canonicalizeMap(v)
                    else -> normalizeText(v.toString())
                }
                "$k:$valStr"
            }
    }

    private fun canonicalizeList(list: List<*>): String {
        return list.joinToString("|") {
            when (it) {
                is List<*> -> canonicalizeList(it)
                is Map<*, *> -> canonicalizeMap(it)
                else -> normalizeText(it.toString())
            }
        }
    }

    private fun canonicalizeJson(json: JSONObject): String {
        val keys = mutableListOf<String>()
        val it = json.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.sort()
        
        return keys.joinToString(",") { key ->
            val value = json.get(key)
            val valStr = when (value) {
                is JSONObject -> canonicalizeJson(value)
                is JSONArray -> canonicalizeJsonArray(value)
                else -> normalizeText(value.toString())
            }
            "${normalizeText(key)}:$valStr"
        }
    }

    private fun canonicalizeJsonArray(array: JSONArray): String {
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            list.add(when (value) {
                is JSONObject -> canonicalizeJson(value)
                is JSONArray -> canonicalizeJsonArray(value)
                else -> normalizeText(value.toString())
            })
        }
        return list.joinToString("|")
    }

    fun normalizeText(text: String): String {
        // 1. Normalize Unicode (NFC)
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        // 2. Lowercase (US Locale for consistency)
        val lower = nfc.lowercase(Locale.US)
        // 3. Normalize whitespace (trim and replace internal whitespace with a single space)
        val singleSpace = lower.replace(Regex("\\s+"), " ").trim()
        // 4. Remove punctuation that doesn't change semantics
        return singleSpace.replace(Regex("[.,!?;:]"), "")
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
