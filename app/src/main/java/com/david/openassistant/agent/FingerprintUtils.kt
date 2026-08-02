package com.david.openassistant.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Versioned canonical execution fingerprint utility for V42.
 * Produces sha256:v1:<lowercase hexadecimal digest>
 */
object FingerprintUtils {
    fun calculateExecutionFingerprint(goal: AgentGoal, task: AgentTask): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val context = EvidenceContextSelector.select(goal, task)
        
        val encoder = CanonicalEncoder()
        encoder.append("goal_id", goal.id)
        encoder.append("task_id", task.id)
        encoder.append("capability", task.capability.name)
        encoder.append("instructions", task.instructions)
        encoder.append("objective", goal.objective)
        
        goal.objectiveContract?.let { contract ->
            encoder.append("objective_contract_v", contract.version.toString())
            contract.contractHash?.let { encoder.append("objective_contract_hash", it) }
        }
        
        encoder.append("context_fingerprint", context.fingerprint)
        
        // Acceptance criteria definition
        task.acceptanceCriteria.sortedBy { it.id }.forEach { crit ->
            encoder.append("ac_id", crit.id)
            encoder.append("ac_desc", crit.description)
        }
        
        // Acceptance check state
        task.acceptanceChecks.sortedBy { it.criterionId }.forEach { check ->
            encoder.append("check_id", check.criterionId)
            encoder.append("check_status", check.status.name)
        }
        
        // Current durable research strategy
        task.activeResearchStrategyJson?.let { encoder.append("research_strategy", it) }
        
        // Query-portfolio fingerprint
        task.queryFingerprints.sorted().forEach { qf ->
            encoder.append("query_fp", qf)
        }
        
        // Relevant task output and accepted-claim state
        task.outputEvidenceId?.let { encoder.append("output_evidence_id", it) }
        goal.acceptedClaims
            .filter { it.taskId == task.id }
            .sortedBy { it.id }
            .forEach { claim ->
                encoder.append("claim_id", claim.id)
                encoder.append("claim_hash", claim.contentHash)
            }

        val canonicalString = encoder.build()
        val hash = digest.digest(canonicalString.toByteArray(StandardCharsets.UTF_8))
        return "sha256:v1:${hash.toHexString()}"
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private class CanonicalEncoder {
        private val buffer = StringBuilder()

        fun append(key: String, value: String) {
            val normalizedValue = normalize(value)
            // Format: len(key):key=len(value):value;
            buffer.append(key.length).append(":").append(key)
                .append("=")
                .append(normalizedValue.length).append(":").append(normalizedValue)
                .append(";")
        }

        private fun normalize(s: String): String {
            // Normalize Unicode (NFC), trim, and normalize irrelevant whitespace.
            return Normalizer.normalize(s, Normalizer.Form.NFC)
                .trim()
                .replace("\\s+".toRegex(), " ")
        }

        fun build(): String = buffer.toString()
    }
}
