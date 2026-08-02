package com.david.openassistant.handoff.model

import org.json.JSONArray
import org.json.JSONObject

data class HandoffManifest(
    val schemaVersion: Int = 2,
    val bundleId: String,
    val parentBundleId: String?,
    val parentSnapshot: ParentSnapshot? = null,
    val contentMode: String, // SOURCE_BASELINE | SOURCE_DELTA | SUPPLEMENT | EXTENDED
    val qualityLevel: String, // SOURCE_ONLY | SOURCE_AND_BUILD | SOURCE_BUILD_AND_RUNTIME | FULL_CERTIFICATION_EVIDENCE
    val createdAtUtc: String,
    val generator: GeneratorInfo,
    val project: ProjectInfo,
    val verification: VerificationStatus,
    val runtime: RuntimeStatus,
    val redaction: RedactionStatus,
    val files: FileCounts
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("bundle_id", bundleId)
        put("parent_bundle_id", parentBundleId ?: JSONObject.NULL)
        put("parent_snapshot", parentSnapshot?.toJson() ?: JSONObject.NULL)
        put("content_mode", contentMode)
        put("quality_level", qualityLevel)
        put("created_at_utc", createdAtUtc)
        put("generator", generator.toJson())
        put("project", project.toJson())
        put("verification", verification.toJson())
        put("runtime", runtime.toJson())
        put("redaction", redaction.toJson())
        put("files", files.toJson())
    }
}

data class GeneratorInfo(val name: String, val version: String) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("version", version)
    }
}

data class ParentSnapshot(
    val bundleId: String,
    val sourceManifestSha256: String,
    val schemaVersion: Int,
    val generatorVersion: String,
    val createdAtUtc: String
) {
    fun toJson() = JSONObject().apply {
        put("bundle_id", bundleId)
        put("source_manifest_sha256", sourceManifestSha256)
        put("schema_version", schemaVersion)
        put("generator_version", generatorVersion)
        put("created_at_utc", createdAtUtc)
    }
}

data class ProjectInfo(
    val applicationId: String?,
    val versionName: String?,
    val versionCode: Int?,
    val sourceManifestSha256: String,
    val evidenceManifestSha256: String,
    val historicalEvidenceIdentitySha256: String? = null,
    val gitCommit: String?,
    val gitDirty: Boolean?,
    val gitBranch: String?,
    val isGit: Boolean,
    val gradleWrapper: String?,
    val agpVersion: String?,
    val kotlinVersion: String?, // Retained for back-compat (maps to root)
    val rootKotlinVersion: String?,
    val catalogKotlinVersion: String?,
    val javaVersion: String?, // Retained for back-compat (maps to tool)
    val toolJavaVersion: String?,
    val appJavaVersion: String?,
    val compileSdk: Int?,
    val targetSdk: Int?,
    val minSdk: Int?,
    val composeBom: String?
) {
    fun toJson() = JSONObject().apply {
        put("application_id", applicationId ?: JSONObject.NULL)
        put("version_name", versionName ?: JSONObject.NULL)
        put("version_code", versionCode ?: JSONObject.NULL)
        put("source_manifest_sha256", sourceManifestSha256)
        put("evidence_manifest_sha256", evidenceManifestSha256)
        put("historical_evidence_identity_sha256", historicalEvidenceIdentitySha256 ?: JSONObject.NULL)
        put("git_commit", gitCommit ?: JSONObject.NULL)
        put("git_dirty", gitDirty ?: JSONObject.NULL)
        put("git_branch", gitBranch ?: JSONObject.NULL)
        put("is_git", isGit)
        put("gradle_wrapper", gradleWrapper ?: JSONObject.NULL)
        put("agp_version", agpVersion ?: JSONObject.NULL)
        put("kotlin_version", kotlinVersion ?: JSONObject.NULL)
        put("root_kotlin_version", rootKotlinVersion ?: JSONObject.NULL)
        put("catalog_kotlin_version", catalogKotlinVersion ?: JSONObject.NULL)
        put("java_version", javaVersion ?: JSONObject.NULL)
        put("tool_java_version", toolJavaVersion ?: JSONObject.NULL)
        put("app_java_version", appJavaVersion ?: JSONObject.NULL)
        put("compile_sdk", compileSdk ?: JSONObject.NULL)
        put("target_sdk", targetSdk ?: JSONObject.NULL)
        put("min_sdk", minSdk ?: JSONObject.NULL)
        put("compose_bom", composeBom ?: JSONObject.NULL)
    }
}

data class VerificationStatus(
    val status: String,
    val unitTests: JSONObject = JSONObject(),
    val lint: JSONObject = JSONObject(),
    val assemble: JSONObject = JSONObject(),
    val connected: JSONObject = JSONObject(),
    val live: JSONObject = JSONObject(),
    val apkIdentity: JSONObject = JSONObject(),
    val toolTests: JSONObject = JSONObject(),
    val acceptance: JSONObject = JSONObject()
) {
    fun toJson() = JSONObject().apply {
        put("status", status)
        put("unit_tests", unitTests)
        put("lint", lint)
        put("assemble", assemble)
        put("connected", connected)
        put("live", live)
        put("apk_identity", apkIdentity)
        put("tool_tests", toolTests)
        put("acceptance", acceptance)
    }
}

data class RuntimeStatus(
    val status: String, 
    val monitorSessionIds: List<String> = emptyList(),
    val mixedVersionDetected: Boolean? = null,
    val unfinishedProviderOperations: Int? = null,
    val unfinishedToolOperations: Int? = null,
    val rawTraceSha256: String? = null,
    val packetZipSha256: String? = null,
    val runtimePacketId: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("status", status)
        put("monitor_session_ids", monitorSessionIds)
        put("mixed_version_detected", mixedVersionDetected ?: JSONObject.NULL)
        put("unfinished_provider_operations", unfinishedProviderOperations ?: JSONObject.NULL)
        put("unfinished_tool_operations", unfinishedToolOperations ?: JSONObject.NULL)
        put("raw_trace_sha256", rawTraceSha256 ?: JSONObject.NULL)
        put("packet_zip_sha256", packetZipSha256 ?: JSONObject.NULL)
        put("runtime_packet_id", runtimePacketId ?: JSONObject.NULL)
    }
}

data class RedactionStatus(
    val secretScanCompleted: Boolean,
    val secretLikeFindings: Int,
    val hiddenReasoningFieldsRemoved: Int
) {
    fun toJson() = JSONObject().apply {
        put("secret_scan_completed", secretScanCompleted)
        put("secret_like_findings", secretLikeFindings)
        put("hidden_reasoning_fields_removed", hiddenReasoningFieldsRemoved)
    }
}

data class FileCounts(
    val included: Int,
    val omitted: Int,
    val chunked: Int
) {
    fun toJson() = JSONObject().apply {
        put("included", included)
        put("omitted", omitted)
        put("chunked", chunked)
    }
}

data class SourceFileEntry(
    val path: String,
    val sizeBytes: Long,
    val lineCount: Int,
    val sha256: String,
    val classification: String,
    val changed: Boolean,
    val critical: Boolean,
    val includedFull: Boolean,
    val chunked: Boolean,
    val payloadState: String = "OMITTED", // INCLUDED_FULL | INCLUDED_CHUNKED | PARENT_REFERENCE | NOT_REQUIRED_FOR_MODE | OMITTED
    val omissionReason: String? = null,
    val isAggregate: Boolean = false,
    val fileCount: Int = 1,
    // OH-023: Integrity measurements
    val topLevelTypeCount: Int = 0,
    val functionCount: Int = 0,
    val todoCount: Int = 0,
    val fixmeCount: Int = 0,
    val suppressionCount: Int = 0,
    val mergeMarkerCount: Int = 0,
    val placeholderMarkerCount: Int = 0,
    val encoding: String = "UTF-8"
) {
    fun toJson() = JSONObject().apply {
        put("path", path)
        put("size_bytes", sizeBytes)
        put("line_count", lineCount)
        put("sha256", sha256)
        put("classification", classification)
        put("changed", changed)
        put("critical", critical)
        put("included_full", includedFull)
        put("chunked", chunked)
        put("payload_state", payloadState)
        put("omission_reason", omissionReason ?: JSONObject.NULL)
        put("is_aggregate", isAggregate)
        if (isAggregate) put("file_count", fileCount)
        put("top_level_type_count", topLevelTypeCount)
        put("function_count", functionCount)
        put("todo_count", todoCount)
        put("fixme_count", fixmeCount)
        put("suppression_count", suppressionCount)
        put("merge_marker_count", mergeMarkerCount)
        put("placeholder_marker_count", placeholderMarkerCount)
        put("encoding", encoding)
    }
}

data class AcceptanceResult(
    val executionId: String,
    val taskPath: String,
    val startedAtUtc: String,
    val finishedAtUtc: String,
    val taskOutcome: String, // SUCCESS | FAILED
    val acceptanceSubjectAlgorithm: String = "path-sha256-v2",
    val acceptanceSubjectSha256: String,
    val sourceManifestSha256: String,
    val toolTestExecutionId: String,
    val checksRequired: List<String>,
    val checksExecuted: List<String>,
    val checksPassed: Int,
    val checksFailed: Int,
    val rollbackStatus: String, // SUCCESS | FAILED | NOT_REQUIRED | NOT_RUN
    val cleanupStatus: String, // SUCCESS | FAILED | NOT_REQUIRED | NOT_RUN
    val securityStatus: String, // PASSED | FAILED
    val privacyStatus: String, // PASSED | FAILED
    val artifactManifestSha256: String,
    val checkArtifacts: Map<String, String> = emptyMap(),
    val metrics: Map<String, Int> = emptyMap(),
    val isolatedFixtureId: String? = null
) {
    fun getStatus(): String {
        val mandatoryPassed = checksRequired.all { checksExecuted.contains(it) }
        val noFailures = checksFailed == 0
        val metricsTruthful = metrics.getOrDefault("files_examined", 0) > 0
        
        return if (taskOutcome == "SUCCESS" && mandatoryPassed && noFailures &&
            rollbackStatus == "SUCCESS" && cleanupStatus == "SUCCESS" && 
            securityStatus == "PASSED" && privacyStatus == "PASSED" &&
            metricsTruthful && !artifactManifestSha256.contains("TODO")) "PASSED" else "FAILED"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("execution_id", executionId)
        put("task_path", taskPath)
        put("started_at_utc", startedAtUtc)
        put("finished_at_utc", finishedAtUtc)
        put("task_outcome", taskOutcome)
        put("acceptance_subject_algorithm", acceptanceSubjectAlgorithm)
        put("acceptance_subject_sha256", acceptanceSubjectSha256)
        put("source_manifest_sha256", sourceManifestSha256)
        put("tool_test_execution_id", toolTestExecutionId)
        put("checks_required", JSONArray(checksRequired))
        put("checks_executed", JSONArray(checksExecuted))
        put("checks_passed", checksPassed)
        put("checks_failed", checksFailed)
        put("rollback_status", rollbackStatus)
        put("cleanup_status", cleanupStatus)
        put("security_status", securityStatus)
        put("privacy_status", privacyStatus)
        put("artifact_manifest_sha256", artifactManifestSha256)
        put("check_artifacts", JSONObject(checkArtifacts))
        put("metrics", JSONObject(metrics))
        put("isolated_fixture_id", isolatedFixtureId ?: JSONObject.NULL)
        put("status", getStatus())
    }
}
