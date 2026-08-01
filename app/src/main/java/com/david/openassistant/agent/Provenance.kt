package com.david.openassistant.agent

import org.json.JSONObject
import org.json.JSONArray

enum class Provenance {
    EXPLICIT_USER_REQUIREMENT,
    SOURCE_DERIVED_FACT,
    MODEL_HYPOTHESIS,
    SYSTEM_POLICY,
}

data class GroundedConstraint(
    val text: String,
    val provenance: Provenance,
    val sourceUrl: String? = null,
    val evidenceId: String? = null,
    val claimId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text)
        put("provenance", provenance.name)
        put("source_url", sourceUrl ?: JSONObject.NULL)
        put("evidence_id", evidenceId ?: JSONObject.NULL)
        put("claim_id", claimId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): GroundedConstraint = GroundedConstraint(
            text = json.getString("text"),
            provenance = Provenance.valueOf(json.getString("provenance")),
            sourceUrl = json.optNullableString("source_url"),
            evidenceId = json.optNullableString("evidence_id"),
            claimId = json.optNullableString("claim_id")
        )
        
        private fun JSONObject.optNullableString(name: String): String? =
            if (!has(name) || isNull(name)) null else getString(name)
    }
}
