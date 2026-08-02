package com.david.openassistant.handoff

import org.json.JSONObject
import java.io.File

class SupplementRequestParser(val projectRoot: File) {

    data class SupplementRequest(
        val files: List<RequestedFile>,
        val parentBundleId: String?
    )

    data class RequestedFile(
        val path: String,
        val includeRelatedTests: Boolean = false
    )

    fun parse(requestFile: File): SupplementRequest {
        if (requestFile.name.endsWith(".json")) {
            val json = JSONObject(requestFile.readText())
            val filesArr = json.getJSONArray("files")
            val files = mutableListOf<RequestedFile>()
            for (i in 0 until filesArr.length()) {
                val obj = filesArr.getJSONObject(i)
                files.add(RequestedFile(
                    path = obj.getString("path"),
                    includeRelatedTests = obj.optBoolean("include_related_tests", false)
                ))
            }
            return SupplementRequest(files, json.optString("parent_bundle_id", null))
        } else {
            // Plain text fallback
            val files = requestFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { RequestedFile(it.trim()) }
            return SupplementRequest(files, null)
        }
    }
}
