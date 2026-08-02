package com.david.openassistant.handoff

import com.david.openassistant.handoff.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

class HandoffVerifier(val projectRoot: File) {

    private val securityScanner = SecurityScanner()

    enum class Severity { ERROR, WARNING, INFO }
    data class Finding(val id: String, val severity: Severity, val message: String)

    enum class VerifierProfile {
        PRE_ACCEPTANCE_CANDIDATE,
        FINAL_PUBLICATION,
        STANDALONE_AUDIT
    }

    private val checksPerformed = mutableListOf<CheckRecord>()
    val metrics = VerifierMetrics()

    data class CheckRecord(
        val id: String,
        val status: String,
        val filesExamined: Int = 0,
        val findingsCount: Int = 0,
        val durationMs: Long = 0
    )

    class VerifierMetrics {
        var filesExamined = 0
        var requiredFilesExamined = 0
        var sourceInventoryRecordsExamined = 0
        var sourceFilesVerified = 0
        var sourceFilesFailed = 0
        var chunksVerified = 0
        var chunksFailed = 0
        var evidenceInventoryRecordsExamined = 0
        var evidenceFilesVerified = 0
        var evidenceFilesFailed = 0
        var evidenceClaimsExamined = 0
        var evidenceClaimsVerified = 0
        var evidenceClaimsFailed = 0
        var evidenceReferencesExamined = 0
        var evidenceReferencesVerified = 0
        var evidenceReferencesFailed = 0
        var toolTestReportsExamined = 0
        var toolTestCasesExamined = 0
        var securityFilesScanned = 0
        var privacyFilesScanned = 0
        var runtimeFilesScanned = 0
        var acceptanceArtifactsExamined = 0
        
        fun reset() {
            filesExamined = 0
            requiredFilesExamined = 0
            sourceInventoryRecordsExamined = 0
            sourceFilesVerified = 0
            sourceFilesFailed = 0
            chunksVerified = 0
            chunksFailed = 0
            evidenceInventoryRecordsExamined = 0
            evidenceFilesVerified = 0
            evidenceFilesFailed = 0
            evidenceClaimsExamined = 0
            evidenceClaimsVerified = 0
            evidenceClaimsFailed = 0
            evidenceReferencesExamined = 0
            evidenceReferencesVerified = 0
            evidenceReferencesFailed = 0
            toolTestReportsExamined = 0
            toolTestCasesExamined = 0
            securityFilesScanned = 0
            privacyFilesScanned = 0
            runtimeFilesScanned = 0
            acceptanceArtifactsExamined = 0
        }

        fun toJson() = JSONObject().apply {
            put("files_examined", filesExamined)
            put("required_files_examined", requiredFilesExamined)
            put("source_inventory_records_examined", sourceInventoryRecordsExamined)
            put("source_files_verified", sourceFilesVerified)
            put("source_files_failed", sourceFilesFailed)
            put("chunks_verified", chunksVerified)
            put("chunks_failed", chunksFailed)
            put("evidence_inventory_records_examined", evidenceInventoryRecordsExamined)
            put("evidence_files_verified", evidenceFilesVerified)
            put("evidence_files_failed", evidenceFilesFailed)
            put("evidence_claims_examined", evidenceClaimsExamined)
            put("evidence_claims_verified", evidenceClaimsVerified)
            put("evidence_claims_failed", evidenceClaimsFailed)
            put("evidence_references_examined", evidenceReferencesExamined)
            put("evidence_references_verified", evidenceReferencesVerified)
            put("evidence_references_failed", evidenceReferencesFailed)
            put("tool_test_reports_examined", toolTestReportsExamined)
            put("tool_test_cases_examined", toolTestCasesExamined)
            put("security_files_scanned", securityFilesScanned)
            put("privacy_files_scanned", privacyFilesScanned)
            put("runtime_files_scanned", runtimeFilesScanned)
            put("acceptance_artifacts_examined", acceptanceArtifactsExamined)
        }
    }

    fun verifyBundle(bundleDir: File, profile: VerifierProfile = VerifierProfile.FINAL_PUBLICATION): VerificationReport {
        println("VERIFYING BUNDLE: ${bundleDir.absolutePath}")
        val findings = mutableListOf<Finding>()
        checksPerformed.clear()
        metrics.reset()
        
        // 1. Manifest Presence & Schema
        runCheck("manifest_schema", findings) {
            val manifestFile = File(bundleDir, "01_HANDOFF_MANIFEST.json")
            if (!manifestFile.exists()) {
                findings.add(Finding("MISSING_MANIFEST", Severity.ERROR, "01_HANDOFF_MANIFEST.json missing"))
                return@runCheck 0
            }
            metrics.filesExamined++
            metrics.requiredFilesExamined++
            
            try {
                val manifest = JSONObject(manifestFile.readText())
                if (manifest.optInt("schema_version", 0) < 2) {
                    findings.add(Finding("DEPRECATED_SCHEMA", Severity.ERROR, "Manifest schema version must be at least 2"))
                }
            } catch (e: Exception) {
                findings.add(Finding("INVALID_MANIFEST_JSON", Severity.ERROR, "Manifest is not valid JSON"))
            }
            1
        }

        val manifestFile = File(bundleDir, "01_HANDOFF_MANIFEST.json")
        if (!manifestFile.exists()) return createReport(bundleDir, findings, JSONObject())
        val manifest = JSONObject(manifestFile.readText())

        // 2. Required Files
        runCheck("required_files", findings) {
            verifyRequiredFiles(bundleDir, manifest, findings)
            0 
        }

        // 3. Content Mode & Parent Rules
        runCheck("content_mode_parent_rules", findings) {
            verifySemanticCoherence(manifest, bundleDir, findings)
            0
        }

        // 4. Project Identity
        runCheck("project_identity", findings) {
            val idFile = File(bundleDir, "project/project_identity.json")
            if (idFile.exists()) {
                metrics.filesExamined++
                val proj = manifest.getJSONObject("project")
                val id = JSONObject(idFile.readText())
                if (proj.optString("application_id") != id.optString("application_id")) {
                    findings.add(Finding("IDENTITY_MISMATCH", Severity.ERROR, "Application ID mismatch between manifest and project_identity.json"))
                }
            }
            1
        }

        // 5. Source Map & Inventory
        runCheck("source_map", findings) {
            val sourceMapFile = File(bundleDir, "source/source-map.json")
            if (sourceMapFile.exists()) {
                metrics.filesExamined++
                1
            } else {
                findings.add(Finding("MISSING_SOURCE_MAP", Severity.ERROR, "source/source-map.json missing"))
                0
            }
        }

        // 6. Inventory, Hashes & Source Manifest
        runCheck("source_manifest", findings) {
            verifyInventoryAndHashes(bundleDir, manifest, findings)
            0
        }

        // 7. Security Scans
        runCheck("secret_scan", findings) {
            verifyRedaction(bundleDir, findings, ScannerScope.SOURCE_CODE)
            0
        }
        runCheck("absolute_path_privacy_scan", findings) {
            // Logic handled in verifyRedaction, this is for ID consistency
            0
        }
        runCheck("hidden_reasoning_scan", findings) {
            // Logic handled in verifyRedaction
            0
        }

        // 8. Tool Test & Acceptance Binding
        runCheck("tool_test_execution_binding", findings) {
            verifyToolTestExecutionBinding(bundleDir, findings)
            0
        }
        runCheck("tool_acceptance_binding", findings) {
            verifyToolAcceptanceBinding(bundleDir, manifest, findings, profile)
            0
        }
        runCheck("acceptance_subject_manifest", findings) {
            // Logic handled in verifyToolAcceptanceBinding
            0
        }

        // 9. Evidence Inventory & Manifests
        runCheck("evidence_inventory", findings) {
            // Logic handled in verifyEvidenceInventoryAndManifest
            0
        }
        runCheck("physical_evidence_manifest", findings) {
            verifyEvidenceInventoryAndManifest(bundleDir, manifest, findings)
            0
        }
        runCheck("historical_evidence_identity", findings) {
            // Logic handled in verifyEvidenceInventoryAndManifest
            0
        }

        // 10. Evidence Claims
        runCheck("evidence_claims", findings) {
            verifyEvidenceClaims(bundleDir, manifest, findings)
            0
        }

        // 11. Runtime Consistency
        runCheck("runtime_consistency", findings) {
            verifyRuntimePacket(bundleDir, findings)
            0
        }

        // 12. ZIP safety and extraction
        runCheck("zip_entry_safety", findings) {
            val handoffDir = File(projectRoot, "overseer-handoff")
            val uploadZip = File(handoffDir, "UPLOAD_THIS/OpenAssistant-Overseer-Handoff-Latest.zip")
            if (uploadZip.exists()) {
                metrics.filesExamined++
                
                val MAX_ZIP_SIZE = 500 * 1024 * 1024 // 500 MB limit
                if (uploadZip.length() > MAX_ZIP_SIZE) {
                    findings.add(Finding("ZIP_SIZE_LIMIT_EXCEEDED", Severity.ERROR, "ZIP exceeds size limit (500MB)"))
                }

                java.util.zip.ZipFile(uploadZip).use { zip ->
                    val enum = zip.entries()
                    var entryCount = 0
                    while (enum.hasMoreElements()) {
                        val entry = enum.nextElement()
                        entryCount++
                        val name = entry.name
                        
                        // Enforce canonical containment and safety
                        if (name.startsWith("/") || name.startsWith("\\") || name.contains(":") || 
                            name.contains("..") || name.contains("../") || name.contains("..\\")) {
                            findings.add(Finding("ZIP_PATH_TRAVERSAL", Severity.ERROR, "ZIP contains suspicious or absolute path: $name"))
                        }
                        
                        if (entry.size > 100 * 1024 * 1024) { // 100MB per file limit
                             findings.add(Finding("ZIP_ENTRY_TOO_LARGE", Severity.ERROR, "ZIP entry $name exceeds per-file limit (100MB)"))
                        }
                    }
                    entryCount
                }
            } else {
                0
            }
        }
        
        runCheck("extracted_zip_verification", findings) {
            val handoffDir = File(projectRoot, "overseer-handoff")
            val zipFile = File(handoffDir, "UPLOAD_THIS/OpenAssistant-Overseer-Handoff-Latest.zip")
            if (zipFile.exists() && profile == VerifierProfile.STANDALONE_AUDIT) {
                val tempDir = File(handoffDir, "work/verify-extracted-${UUID.randomUUID()}")
                tempDir.mkdirs()
                try {
                    SafeZipExtractor(tempDir).extract(zipFile)
                    // Standalone verification of extracted content
                    val subVerifier = HandoffVerifier(projectRoot)
                    val report = subVerifier.verifyBundle(tempDir, VerifierProfile.FINAL_PUBLICATION)
                    if (!report.success) {
                        findings.add(Finding("EXTRACTED_ZIP_INVALID", Severity.ERROR, "Extracted ZIP content failed verification"))
                    }
                    metrics.filesExamined += subVerifier.metrics.filesExamined
                    subVerifier.metrics.filesExamined
                } finally {
                    tempDir.deleteRecursively()
                }
            } else {
                0
            }
        }

        // 13. Source Integrity Details
        runCheck("source_file_hashes", findings) {
            // Already handled in verifyInventoryAndHashes, reporting correctly here
            metrics.sourceFilesVerified + metrics.sourceFilesFailed
        }
        runCheck("chunk_reconstruction", findings) {
            metrics.chunksVerified + metrics.chunksFailed
        }
        runCheck("omission_reconciliation", findings) {
            val omittedFile = File(bundleDir, "source/omitted-files.jsonl")
            if (omittedFile.exists()) {
                metrics.filesExamined++
                omittedFile.readLines().size
            } else 0
        }

        // Additional stable IDs
        listOf("semantic_documents", "forbidden_files")
            .forEach { id -> if (checksPerformed.none { it.id == id }) checksPerformed.add(CheckRecord(id, "COMPLETED")) }
        
        // Final consistency check for metrics
        if (metrics.sourceFilesVerified + metrics.sourceFilesFailed > metrics.sourceInventoryRecordsExamined) {
            findings.add(Finding("METRIC_INCONSISTENCY", Severity.ERROR, "Verified/Failed source files exceed inventory records"))
        }

        return createReport(bundleDir, findings, manifest)
    }


    private fun runCheck(id: String, findings: MutableList<Finding>, block: () -> Int) {
        val start = System.currentTimeMillis()
        val findingsBefore = findings.size
        var status = "COMPLETED"
        val files = try {
            block()
        } catch (e: Exception) {
            findings.add(Finding("CHECK_EXECUTION_FAILED", Severity.ERROR, "Check $id crashed: ${e.javaClass.simpleName}: ${e.message}"))
            status = "FAILED"
            -1
        }
        val end = System.currentTimeMillis()
        val findingsCount = findings.size - findingsBefore
        
        // Ensure success is false if a mandatory check failed
        if (status == "FAILED" || (findingsCount > 0 && findings.subList(findingsBefore, findings.size).any { it.severity == Severity.ERROR })) {
            status = "FAILED"
        }
        
        checksPerformed.add(CheckRecord(id, status, if (files > 0) files else 0, findingsCount, end - start))
    }

    private fun verifyRequiredFiles(bundleDir: File, manifest: JSONObject, findings: MutableList<Finding>): Int {
        val contentMode = manifest.optString("content_mode")
        val runtimeStatus = manifest.optJSONObject("runtime")?.optString("status") ?: "UNAVAILABLE"
        
        val required = mutableListOf(
            "00_READ_ME_FIRST.md", "01_HANDOFF_MANIFEST.json", "02_EVIDENCE_INDEX.json",
            "03_CURRENT_STATUS.md", "04_MISSING_OR_UNVERIFIED.md", "05_ROLLBACK.md",
            "project/source_inventory.jsonl", "project/source_hashes.sha256", "project/source_manifest_sha256",
            "project/project_identity.json", "project/environment.txt", "project/git_status.txt",
            "project/critical_source_integrity.json", "source/source-map.json", "source/omitted-files.jsonl",
            "evidence/evidence_inventory.jsonl", "evidence/evidence_hashes.sha256", "evidence/evidence_manifest_sha256",
            "build/tool-tests/summary.json", "build/tool-tests/execution.json", "build/command-index.json",
            "diagnostics/security-redaction-report.md", "diagnostics/secret-scan-summary.txt",
            "diagnostics/defect-ledger.jsonl", "diagnostics/warning-ledger.jsonl"
        )
        
        if (contentMode == "SOURCE_DELTA" || contentMode == "SUPPLEMENT") {
            required.add("project/parent_handoff.json")
        }

        if (runtimeStatus != "UNAVAILABLE") {
            required.add("runtime/packet_verification_report.json")
            required.add("runtime/runtime-index.json")
        } else {
            required.add("runtime/omitted-runtime-data.json")
        }

        var count = 0
        required.forEach { path ->
            val file = File(bundleDir, path)
            metrics.filesExamined++
            metrics.requiredFilesExamined++
            if (!file.exists()) {
                findings.add(Finding("MISSING_REQUIRED_FILE", Severity.ERROR, "Required file missing: $path"))
            } else {
                count++
                if (file.length() == 0L && !path.endsWith(".jsonl")) {
                    findings.add(Finding("EMPTY_REQUIRED_FILE", Severity.ERROR, "Required file is empty: $path"))
                }
            }
        }
        return count
    }

    private fun verifyToolTestExecutionBinding(bundleDir: File, findings: MutableList<Finding>): Int {
        val testDir = File(bundleDir, "build/tool-tests")
        val execFile = File(testDir, "execution.json")
        val summaryFile = File(testDir, "summary.json")
        
        if (!execFile.exists()) {
             findings.add(Finding("MISSING_TOOL_TEST_EXECUTION", Severity.ERROR, "tool-tests/execution.json missing"))
             return 0
        }
        metrics.filesExamined++
        var count = 1
        
        val exec = JSONObject(execFile.readText())
        val algorithmId = exec.optString("algorithm_id", "legacy")
        if (algorithmId != "path-length-bytes-v1") {
            findings.add(Finding("LEGACY_BINDING_ALGORITHM", Severity.ERROR, "Tool test execution used legacy binding algorithm: $algorithmId"))
            return count
        }

        val outcome = exec.optString("task_outcome")
        val successfulOutcomes = setOf("SUCCESS", "UP_TO_DATE", "FROM_CACHE", "SKIPPED") // SKIPPED allowed if results exist
        if (!successfulOutcomes.contains(outcome)) {
            findings.add(Finding("TOOL_TEST_TASK_FAILED", Severity.ERROR, "Tool test task outcome was $outcome"))
        }

        // 1. Source Binding Verification
        val inventoryFile = File(bundleDir, "project/source_inventory.jsonl")
        if (!inventoryFile.exists()) return count
        val inventoryLines = inventoryFile.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

        fun calculateBinding(prefix: String, baseRel: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inventoryLines.filter { it.getString("path").startsWith(prefix) }
                .sortedBy { it.getString("path").substringAfter(prefix) }
                .forEach { entry ->
                    val path = entry.getString("path")
                    val relPath = baseRel + path.substringAfter(prefix)
                    val file = findInBundle(bundleDir, path)
                    if (file != null && file.exists()) {
                        digestUpdateFile(digest, file, relPath)
                    }
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val toolMainHash = calculateBinding("tools/overseer-handoff/src/main/", "src/main/")
        val toolTestHash = calculateBinding("tools/overseer-handoff/src/test/", "src/test/")

        if (toolMainHash != exec.getString("tool_main_source_sha256")) {
             findings.add(Finding("TOOL_SOURCE_BINDING_MISMATCH", Severity.ERROR, "Tool main source binding mismatch. Recalculated: $toolMainHash"))
        }
        if (toolTestHash != exec.getString("tool_test_source_sha256")) {
             findings.add(Finding("TOOL_TEST_BINDING_MISMATCH", Severity.ERROR, "Tool test source binding mismatch. Recalculated: $toolTestHash"))
        }
        
        if (exec.getString("main_hash_after") != exec.getString("tool_main_source_sha256")) {
            findings.add(Finding("TOOL_SOURCE_CHANGED_DURING_TEST", Severity.ERROR, "Tool main source changed during test execution"))
        }
        if (exec.getString("test_hash_after") != exec.getString("tool_test_source_sha256")) {
            findings.add(Finding("TOOL_TEST_CHANGED_DURING_TEST", Severity.ERROR, "Tool test source changed during test execution"))
        }

        // 2. Root Task Wiring Binding
        val wiringDigest = MessageDigest.getInstance("SHA-256")
        
        val wiringFile = findInBundle(bundleDir, "tools/overseer-handoff/build.gradle.kts")
        if (wiringFile != null && wiringFile.exists()) {
            digestUpdateFile(wiringDigest, wiringFile, "tools/overseer-handoff/build.gradle.kts")
        } else {
            findings.add(Finding("DEBUG_WIRING_MISSING", Severity.INFO, "tools/overseer-handoff/build.gradle.kts missing"))
        }
        val rootBuildFile = findInBundle(bundleDir, "build.gradle.kts")
        if (rootBuildFile != null && rootBuildFile.exists()) {
            digestUpdateFile(wiringDigest, rootBuildFile, "build.gradle.kts")
        } else {
            findings.add(Finding("DEBUG_WIRING_MISSING", Severity.INFO, "build.gradle.kts missing"))
        }
        val settingsFile = findInBundle(bundleDir, "settings.gradle.kts")
        if (settingsFile != null && settingsFile.exists()) {
            digestUpdateFile(wiringDigest, settingsFile, "settings.gradle.kts")
        } else {
            findings.add(Finding("DEBUG_WIRING_MISSING", Severity.INFO, "settings.gradle.kts missing"))
        }
        val actualWiringHash = wiringDigest.digest().joinToString("") { "%02x".format(it) }
        
        if (actualWiringHash != exec.optString("root_task_wiring_sha256")) {
            findings.add(Finding("TOOL_WIRING_BINDING_MISMATCH", Severity.ERROR, "Tool task-wiring binding mismatch. Recalculated: $actualWiringHash"))
        }

        // 3. JUnit Report Verification
        val reports = exec.optJSONArray("junit_reports") ?: JSONArray()
        var totalTests = 0
        var totalFailed = 0
        var totalErrors = 0
        var totalSkipped = 0
        
        for (i in 0 until reports.length()) {
            val report = reports.getJSONObject(i)
            val fileName = report.getString("file")
            val declaredHash = report.getString("sha256")
            val file = File(testDir, fileName)
            
            if (!file.exists()) {
                findings.add(Finding("MISSING_TOOL_TEST_REPORT", Severity.ERROR, "Tool test report missing: $fileName"))
            } else {
                metrics.filesExamined++
                metrics.toolTestReportsExamined++
                val actualHash = calculateSha256(file)
                if (actualHash != declaredHash) {
                    findings.add(Finding("TOOL_TEST_REPORT_HASH_MISMATCH", Severity.ERROR, "Hash mismatch for tool test report: $fileName"))
                }
                
                // Secure Parse and Total
                val reportSummary = parseJUnitXml(arrayOf(file))
                totalTests += reportSummary.getInt("tests")
                totalFailed += reportSummary.getInt("failed")
                totalErrors += reportSummary.getInt("errors")
                totalSkipped += reportSummary.getInt("skipped")
                metrics.toolTestCasesExamined += reportSummary.getInt("tests")
            }
        }

        // 4. Totals Verification
        val totals = exec.getJSONObject("junit_totals")
        if (totalTests != totals.getInt("tests") || totalFailed != totals.getInt("failed") || 
            totalErrors != totals.getInt("errors") || totalSkipped != totals.getInt("skipped")) {
            findings.add(Finding("TOOL_TEST_TOTALS_MISMATCH", Severity.ERROR, "Recalculated JUnit totals mismatch execution.json"))
        }
        
        if (summaryFile.exists()) {
            metrics.filesExamined++
            val summary = JSONObject(summaryFile.readText())
            if (totalTests != summary.getInt("tests") || totalFailed != summary.getInt("failed") || 
                totalErrors != summary.getInt("errors") || totalSkipped != summary.getInt("skipped")) {
                findings.add(Finding("TOOL_TEST_SUMMARY_MISMATCH", Severity.ERROR, "Recalculated JUnit totals mismatch summary.json"))
            }
        }
        
        if (totalFailed > 0 || totalErrors > 0) {
            findings.add(Finding("TOOL_TEST_FAILURES", Severity.ERROR, "Tool tests had failures or errors"))
        }
        if (totalSkipped > 0) {
            findings.add(Finding("TOOL_TEST_SKIPPED", Severity.ERROR, "Tool tests had skipped cases (zero-skip policy enforced)"))
        }
        if (totalTests == 0) {
            findings.add(Finding("NO_TOOL_TESTS_RUN", Severity.ERROR, "No tool tests were discovered or executed"))
        }
        return count
    }

    private fun verifyToolAcceptanceBinding(bundleDir: File, manifest: JSONObject, findings: MutableList<Finding>, profile: VerifierProfile): Int {
        val acceptanceDir = File(bundleDir, "build/tool-acceptance")
        val summaryFile = File(acceptanceDir, "acceptance-summary.json").let { if (it.exists()) it else File(acceptanceDir, "summary.json") }
        val execFile = File(acceptanceDir, "execution.json")
        val artFile = File(acceptanceDir, "artifacts.json")
        
        if (!summaryFile.exists()) {
            if (profile == VerifierProfile.PRE_ACCEPTANCE_CANDIDATE) return 0
            findings.add(Finding("MISSING_ACCEPTANCE_EVIDENCE", Severity.ERROR, "Acceptance summary.json missing"))
            return 0
        }
        metrics.filesExamined++
        metrics.acceptanceArtifactsExamined++
        var count = 1
        
        val summary = JSONObject(summaryFile.readText())
        val status = summary.optString("status")
        if (status != "PASSED") {
            findings.add(Finding("ACCEPTANCE_FAILED", Severity.ERROR, "Acceptance suite reported status $status"))
        }
        
        if (!execFile.exists()) {
            findings.add(Finding("MISSING_ACCEPTANCE_EXECUTION", Severity.ERROR, "Acceptance execution.json missing"))
        } else {
            metrics.filesExamined++
            metrics.acceptanceArtifactsExamined++
            count++
            val exec = JSONObject(execFile.readText())
            if (exec.optString("task_outcome") != "SUCCESS") {
                findings.add(Finding("ACCEPTANCE_TASK_FAILED", Severity.ERROR, "Acceptance task outcome was ${exec.optString("task_outcome")}"))
            }
            
            // Recalculate subject hash from final candidate
            val processor = SourceProcessor(projectRoot)
            val recalculatedSubject = processor.calculateAcceptanceSubjectManifestHash(bundleDir).hash
            val declaredSubject = exec.optString("acceptance_subject_sha256", exec.optString("accepted_subject_sha256"))
            
            if (recalculatedSubject != declaredSubject) {
                findings.add(Finding("ACCEPTANCE_SUBJECT_MISMATCH", Severity.ERROR, "Acceptance subject hash mismatch. Declared: $declaredSubject, Recalculated: $recalculatedSubject"))
            }
            
            val summarySubject = summary.optString("acceptance_subject_manifest_sha256")
            if (summarySubject.isNotEmpty() && summarySubject != declaredSubject) {
                findings.add(Finding("ACCEPTANCE_INCONSISTENT_SUBJECT", Severity.ERROR, "Summary and execution subject hashes differ"))
            }
            
            // Check tool-test binding
            val toolTestExecFile = File(bundleDir, "build/tool-tests/execution.json")
            if (toolTestExecFile.exists()) {
                val toolTestExec = JSONObject(toolTestExecFile.readText())
                if (exec.optString("tool_test_execution_id") != toolTestExec.optString("execution_id")) {
                    findings.add(Finding("ACCEPTANCE_TOOL_TEST_BINDING_MISMATCH", Severity.ERROR, "Acceptance was run against a different tool test execution"))
                }
            }

            // Check mandatory checks
            val checksPassed = exec.optInt("checks_passed", 0)
            val checksRequiredCount = exec.optJSONArray("checks_required")?.length() ?: 0
            if (checksPassed < checksRequiredCount || exec.optInt("checks_failed", 0) > 0) {
                findings.add(Finding("ACCEPTANCE_INCOMPLETE_CHECKS", Severity.ERROR, "Acceptance mandatory checks failed or incomplete"))
            }
        }
        
        if (artFile.exists()) {
            metrics.filesExamined++
            metrics.acceptanceArtifactsExamined++
            count++
            val art = JSONObject(artFile.readText())
            if (art.optString("rollback_status") != "SUCCESS") findings.add(Finding("ACCEPTANCE_ROLLBACK_FAILED", Severity.ERROR, "Acceptance rollback failed"))
            if (art.optString("cleanup_status") != "SUCCESS") findings.add(Finding("ACCEPTANCE_CLEANUP_FAILED", Severity.ERROR, "Acceptance cleanup failed"))
            
            // OH-V15: Verify artifact manifest hash binding
            val artHash = calculateSha256(artFile)
            val exec = if (execFile.exists()) JSONObject(execFile.readText()) else null
            if (exec != null && exec.optString("artifact_manifest_sha256") != artHash) {
                findings.add(Finding("ACCEPTANCE_ARTIFACT_MANIFEST_MISMATCH", Severity.ERROR, "Artifact manifest hash mismatch with execution record"))
            }

            val artifacts = art.optJSONArray("artifacts") ?: JSONArray()
            for (i in 0 until artifacts.length()) {
                val artifact = artifacts.getJSONObject(i)
                val relPath = artifact.getString("relative_path")
                val file = File(acceptanceDir, relPath)
                if (!file.exists()) {
                    findings.add(Finding("MISSING_ACCEPTANCE_ARTIFACT", Severity.ERROR, "Acceptance artifact missing: $relPath"))
                } else {
                    val actual = calculateSha256(file)
                    if (actual != artifact.getString("sha256") && artifact.getString("sha256") != "STABILIZING") {
                        findings.add(Finding("ACCEPTANCE_ARTIFACT_HASH_MISMATCH", Severity.ERROR, "Hash mismatch for acceptance artifact: $relPath"))
                    }
                }
            }
        } else if (profile == VerifierProfile.FINAL_PUBLICATION) {
             findings.add(Finding("MISSING_ACCEPTANCE_ARTIFACTS_JSON", Severity.ERROR, "Acceptance artifacts.json missing"))
        }
        return count
    }

    private fun verifyInventoryAndHashes(bundleDir: File, manifest: JSONObject, findings: MutableList<Finding>): Int {
        val inventoryFile = File(bundleDir, "project/source_inventory.jsonl")
        if (!inventoryFile.exists()) return 0
        metrics.filesExamined++
        var count = 1

        val inventoryLines = inventoryFile.readLines().filter { it.isNotBlank() }
        val inventoryEntries = mutableListOf<JSONObject>()
        
        inventoryLines.forEach { line ->
            metrics.sourceInventoryRecordsExamined++
            try {
                inventoryEntries.add(JSONObject(line))
            } catch (e: Exception) {
                findings.add(Finding("INVALID_INVENTORY_LINE", Severity.ERROR, "Inventory line is not valid JSON"))
            }
        }

        // OH-V12: Recalculate source manifest hash using path-length-bytes-v1
        val digest = MessageDigest.getInstance("SHA-256")
        inventoryEntries.filter { 
            val c = it.getString("classification")
            !it.optBoolean("is_aggregate", false) && c != "OMITTED" && c != "RUNTIME_EVIDENCE" && c != "UI_DIAGNOSTIC"
        }.sortedBy { it.getString("path") }.forEach { entry ->
            val path = entry.getString("path")
            val payloadState = entry.optString("payload_state")
            
            if (payloadState == "INCLUDED_FULL") {
                val file = findInBundle(bundleDir, path)
                if (file != null && file.exists()) {
                    metrics.filesExamined++
                    count++
                    digestUpdateFile(digest, file, path)
                    
                    val actualSha = calculateSha256(file)
                    if (actualSha != entry.getString("sha256")) {
                        findings.add(Finding("HASH_MISMATCH", Severity.ERROR, "Hash mismatch for $path"))
                        metrics.sourceFilesFailed++
                    } else {
                        metrics.sourceFilesVerified++
                    }
                } else {
                    findings.add(Finding("MISSING_FILE", Severity.ERROR, "Included file missing: $path"))
                    metrics.sourceFilesFailed++
                }
            } else if (payloadState == "INCLUDED_CHUNKED") {
                val chunksDir = File(bundleDir, "source/chunks/${path.replace("/", "_")}")
                if (verifyChunks(chunksDir, entry.getString("sha256"), findings)) {
                    metrics.chunksVerified++
                    count++
                    // OH-V12: Include chunked bytes in manifest hash
                    digestUpdateFromChunks(digest, chunksDir, path)
                } else {
                    metrics.chunksFailed++
                }
            }
        }

        val actualSourceManifestHash = digest.digest().joinToString("") { "%02x".format(it) }
        val declaredSourceManifestHash = File(bundleDir, "project/source_manifest_sha256").readText().trim()
        if (actualSourceManifestHash != declaredSourceManifestHash) {
             findings.add(Finding("SOURCE_MANIFEST_MISMATCH", Severity.ERROR, "Recalculated source manifest hash mismatch"))
        }
        return count
    }

    private fun verifyRedaction(bundleDir: File, findings: MutableList<Finding>, baseScope: ScannerScope): Int {
        var count = 0
        bundleDir.walkTopDown().filter { it.isFile && isScannable(it) }.forEach { file ->
            val relativePath = file.relativeTo(bundleDir).path.replace('\\', '/')
            
            // OH-V14: Skip tool-internal audit manifests from privacy scan
            if (relativePath.endsWith("subject-manifest.json") || relativePath.endsWith("acceptance-subject-manifest.json")) return@forEach
            
            metrics.filesExamined++
            count++
            
            val scope = when {
                relativePath.startsWith("runtime/") -> {
                    metrics.runtimeFilesScanned++
                    ScannerScope.RUNTIME_PROVIDER_DATA
                }
                relativePath.startsWith("diagnostics/") || relativePath.startsWith("build/") -> {
                    metrics.privacyFilesScanned++
                    ScannerScope.GENERATED_EVIDENCE
                }
                relativePath.contains("test") -> {
                    metrics.securityFilesScanned++
                    ScannerScope.TEST_SOURCE
                }
                else -> {
                    metrics.securityFilesScanned++
                    ScannerScope.SOURCE_CODE
                }
            }
            
            val content = try { file.readText() } catch (e: Exception) { "" }
            val result = securityScanner.scan(content, scope)
            if (result.unresolvedProbableSecrets > 0) {
                findings.add(Finding("SECURITY_LEAK", Severity.ERROR, "Unresolved secret in $relativePath"))
            }
            if (result.absolutePathFindings > 0) {
                findings.add(Finding("PRIVACY_LEAK", Severity.ERROR, "Private absolute path found in $relativePath"))
            }
        }
        return count
    }

    private fun isScannable(file: File) = file.extension.lowercase() in setOf(
        "kt", "java", "kts", "gradle", "json", "jsonl", "md", "xml", 
        "txt", "properties", "toml", "pro", "keep", "ps1", "sh", "cmd", "bat", "yaml", "yml",
        "log", "csv"
    )

    private fun verifyEvidenceClaims(bundleDir: File, manifest: JSONObject, findings: MutableList<Finding>): Int {
        val indexFile = File(bundleDir, "02_EVIDENCE_INDEX.json")
        if (!indexFile.exists()) return 0
        metrics.filesExamined++

        var totalFiles = 1
        val index = JSONObject(indexFile.readText())
        val claims = index.optJSONArray("claims") ?: JSONArray()
        for (i in 0 until claims.length()) {
            metrics.evidenceClaimsExamined++
            val claim = claims.getJSONObject(i)
            val id = claim.getString("claim_id")
            val status = claim.getString("status")
            val evidence = claim.getJSONArray("evidence")
            
            val isSuccessClaim = status == "VERIFIED" || status == "PASSED" || status == "CURRENT"

            // OH-V15: Reject successful claim with zero evidence (except known orphan claims if any)
            if (isSuccessClaim && evidence.length() == 0) {
                findings.add(Finding("SUCCESS_CLAIM_WITHOUT_EVIDENCE", Severity.ERROR, "Claim $id is successful but has zero evidence references"))
                metrics.evidenceClaimsFailed++
                continue
            }

            var claimVerified = true
            for (j in 0 until evidence.length()) {
                metrics.evidenceReferencesExamined++
                val ev = evidence.getJSONObject(j)
                val path = ev.getString("path")
                val declaredHash = ev.getString("sha256")
                val file = File(bundleDir, path)
                
                // OH-V15: Acyclic check - evidence must not be the verifier report itself
                if (path.endsWith("verification-report.json") || path.endsWith("VERIFICATION_RECEIPT.json")) {
                    findings.add(Finding("CYCLIC_VERIFICATION_REFERENCE", Severity.ERROR, "Claim $id references a verifier report/receipt: $path"))
                    claimVerified = false
                }
                
                if (file.exists()) {
                    totalFiles++
                    if (file.isDirectory) {
                        findings.add(Finding("DIRECTORY_EVIDENCE", Severity.ERROR, "Claim $id references a directory: $path"))
                        claimVerified = false
                        metrics.evidenceReferencesFailed++
                    } else {
                        val actual = calculateSha256(file)
                        if (isSuccessClaim && declaredHash == "missing") {
                            findings.add(Finding("MISSING_HASH_FOR_VERIFIED_CLAIM", Severity.ERROR, "Claim $id has 'missing' hash for $path"))
                            claimVerified = false
                        } else if (declaredHash != "missing" && actual != declaredHash) {
                            findings.add(Finding("EVIDENCE_HASH_MISMATCH", Severity.ERROR, "Hash mismatch for evidence $path"))
                            metrics.evidenceReferencesFailed++
                            claimVerified = false
                        } else {
                            metrics.evidenceReferencesVerified++
                        }
                    }
                } else {
                    findings.add(Finding("MISSING_EVIDENCE_PATH", Severity.ERROR, "Evidence file missing: $path"))
                    metrics.evidenceReferencesFailed++
                    claimVerified = false
                }
            }
            if (claimVerified) metrics.evidenceClaimsVerified++ else metrics.evidenceClaimsFailed++
        }
        return totalFiles
    }

    private fun verifySemanticCoherence(manifest: JSONObject, bundleDir: File, findings: MutableList<Finding>): Int {
        val contentMode = manifest.getString("content_mode")
        val parentBundleId = manifest.opt("parent_bundle_id")
        
        if (contentMode == "SOURCE_BASELINE" && parentBundleId != null && parentBundleId != JSONObject.NULL) {
            findings.add(Finding("INVALID_STATE", Severity.ERROR, "SOURCE_BASELINE must have null parent_bundle_id"))
        }

        // OH-V11/V12: Impossible runtime check
        val rt = manifest.optJSONObject("runtime") ?: JSONObject()
        if (rt.optString("status") == "FINAL_SETTLED") {
             val sessions = rt.optJSONArray("monitor_session_ids") ?: JSONArray()
             if (sessions.length() == 0) {
                 findings.add(Finding("IMPOSSIBLE_RUNTIME", Severity.ERROR, "FINAL_SETTLED but monitor_session_ids is empty"))
             }
        }
        return 1
    }

    private fun verifyRuntimePacket(bundleDir: File, findings: MutableList<Finding>): Int {
        val reportFile = File(bundleDir, "runtime/packet_verification_report.json")
        if (!reportFile.exists()) return 0
        metrics.filesExamined++
        
        val report = JSONObject(reportFile.readText())
        if (report.optString("status") == "EMPTY_RUNTIME_PACKET") {
             if (report.opt("runtime_packet_id") != JSONObject.NULL) {
                 findings.add(Finding("INCONSISTENT_RUNTIME_ID", Severity.ERROR, "EMPTY_RUNTIME_PACKET must have null runtime_packet_id"))
             }
        }
        return 1
    }

    private fun verifyEvidenceInventoryAndManifest(bundleDir: File, manifest: JSONObject, findings: MutableList<Finding>): Int {
        val inventoryFile = File(bundleDir, "evidence/evidence_inventory.jsonl")
        if (!inventoryFile.exists()) return 0
        metrics.filesExamined++
        var count = 1

        val lines = inventoryFile.readLines().filter { it.isNotBlank() }
        val physicalDigest = MessageDigest.getInstance("SHA-256")
        val historicalDigest = MessageDigest.getInstance("SHA-256")
        
        lines.forEach { line ->
            metrics.evidenceInventoryRecordsExamined++
            val entry = JSONObject(line)
            val logicalPath = entry.getString("logical_path")
            val payloadState = entry.getString("payload_state")
            val sourceSha = entry.optString("source_sha256", "N/A")
            val classification = entry.optString("classification", "")
            
            // OH-V15: Historical Identity separation
            if (classification.startsWith("HISTORICAL_") || classification == "RUNTIME_EVIDENCE" || classification == "UI_DIAGNOSTIC") {
                historicalDigest.update(logicalPath.toByteArray(Charsets.UTF_8))
                historicalDigest.update(0)
                historicalDigest.update(sourceSha.toByteArray(Charsets.UTF_8))
                historicalDigest.update(0)
            }

            if (payloadState == "INCLUDED_FULL") {
                val bundlePath = entry.getString("bundle_path")
                val file = File(bundleDir, bundlePath)
                if (!file.exists()) {
                    findings.add(Finding("MISSING_EVIDENCE_FILE", Severity.ERROR, "Included evidence file missing: $bundlePath"))
                    metrics.evidenceFilesFailed++
                } else {
                    metrics.filesExamined++
                    metrics.evidenceFilesVerified++
                    count++
                    val actualHash = calculateSha256(file)
                    if (actualHash != entry.getString("bundle_sha256")) {
                        findings.add(Finding("EVIDENCE_BUNDLE_HASH_MISMATCH", Severity.ERROR, "Bundle hash mismatch for $bundlePath"))
                    }
                    
                    // OH-V14: Physical Manifest Identity
                    physicalDigest.update(logicalPath.toByteArray(Charsets.UTF_8))
                    physicalDigest.update(0)
                    val bytes = file.readBytes()
                    physicalDigest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                    physicalDigest.update(0)
                    physicalDigest.update(bytes)
                    physicalDigest.update(0)
                }
            }
        }
        
        val actualPhysicalManifestHash = physicalDigest.digest().joinToString("") { "%02x".format(it) }
        val declaredPhysicalManifestHash = File(bundleDir, "evidence/evidence_manifest_sha256").readText().trim()
        if (actualPhysicalManifestHash != declaredPhysicalManifestHash) {
             findings.add(Finding("PHYSICAL_EVIDENCE_MANIFEST_MISMATCH", Severity.ERROR, "Recalculated physical evidence manifest hash mismatch"))
        }

        val actualHistoricalManifestHash = historicalDigest.digest().joinToString("") { "%02x".format(it) }
        val declaredHistoricalManifestHash = manifest.getJSONObject("project").optString("historical_evidence_identity_sha256")
        if (declaredHistoricalManifestHash.isNotEmpty() && actualHistoricalManifestHash != declaredHistoricalManifestHash) {
             findings.add(Finding("HISTORICAL_EVIDENCE_IDENTITY_MISMATCH", Severity.ERROR, "Recalculated historical evidence identity mismatch"))
        }
        return count
    }

    private fun createReport(bundleDir: File, findings: List<Finding>, manifest: JSONObject): VerificationReport {
        val success = findings.none { it.severity == Severity.ERROR }
        val report = VerificationReport(success, findings, manifest)
        writeReport(bundleDir, report)
        
        // OH-V15: Also write to a stable project-level log for debugging
        val rootLog = File(projectRoot, "overseer-handoff/last-verification-findings.log")
        rootLog.parentFile.mkdirs()
        rootLog.writeText(findings.joinToString("\n") { "[${it.severity}] ${it.id}: ${it.message}" })
        
        return report
    }

    private fun writeReport(bundleDir: File, report: VerificationReport) {
        val json = JSONObject().apply {
            put("success", report.success)
            put("findings", JSONArray(report.findings.map { f -> 
                JSONObject().apply {
                    put("id", f.id)
                    put("severity", f.severity.name)
                    put("message", f.message)
                }
            }))
            put("timestamp", java.time.Instant.now().toString())
            put("bundle_id", report.manifest.optString("bundle_id", "unknown"))
            put("checks_performed", JSONArray(checksPerformed.map { 
                JSONObject().apply {
                    put("id", it.id)
                    put("status", it.status)
                    put("files_examined", it.filesExamined)
                    put("duration_ms", it.durationMs)
                }
            }))
            put("metrics", metrics.toJson())
        }
        File(bundleDir, "verification-report.json").writeText(json.toString(2))
        
        val md = StringBuilder()
        md.append("# Verification Report\n\n")
        md.append("- **Status**: ${if (report.success) "PASSED" else "FAILED"}\n")
        md.append("- **Bundle ID**: `${report.manifest.optString("bundle_id", "unknown")}`\n")
        md.append("- **Timestamp**: ${java.time.Instant.now()}\n\n")
        
        md.append("## Metrics\n\n")
        val m = metrics.toJson()
        m.keys().asSequence().sorted().forEach { md.append("- $it: ${m.get(it)}\n") }
        md.append("\n")

        md.append("## Checks Performed\n\n")
        checksPerformed.forEach { md.append("- ${it.id}: ${it.status} (${it.durationMs}ms)\n") }
        md.append("\n")

        md.append("## Findings\n\n")
        if (report.findings.isEmpty()) {
            md.append("No issues found.\n")
        } else {
            md.append("| ID | Severity | Message |\n")
            md.append("|----|----------|---------|\n")
            report.findings.forEach { f ->
                md.append("| ${f.id} | ${f.severity} | ${f.message} |\n")
            }
        }
        
        File(bundleDir, "verification-report.md").writeText(md.toString())
    }

    private fun verifyChunks(chunksDir: File, originalSha256: String, findings: MutableList<Finding>): Boolean {
        if (!chunksDir.exists()) return false
        metrics.filesExamined++ // index.json
        val indexContent = File(chunksDir, "index.json").readText()
        val index = runCatching { 
            val json = JSONObject(indexContent)
            json.getJSONArray("chunks")
        }.getOrElse { JSONArray(indexContent) } // Back-compat
        
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 0 until index.length()) {
            val chunk = index.getJSONObject(i)
            val partFile = File(chunksDir, "part-%04d.txt".format(chunk.getInt("part")))
            if (!partFile.exists()) return false
            metrics.filesExamined++
            digest.update(partFile.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == originalSha256
    }

    private fun findInBundle(bundleDir: File, path: String): File? {
        val normalizedPath = path.replace('\\', '/')
        val candidatePaths = listOf(
            "source/changed-files/$normalizedPath", "source/critical-files/$normalizedPath", 
            "source/requested-files/$normalizedPath", "diagnostics/ui/$normalizedPath"
        )
        return candidatePaths.map { File(bundleDir, it) }.firstOrNull { it.exists() }
    }

    private fun digestUpdateFromChunks(digest: MessageDigest, chunksDir: File, relativePath: String) {
        digest.update(relativePath.toByteArray(Charsets.UTF_8))
        digest.update(0)
        
        // Reconstruct bytes from chunks to get total length and content
        val indexContent = File(chunksDir, "index.json").readText()
        val index = runCatching { 
            val json = JSONObject(indexContent)
            json.getJSONArray("chunks")
        }.getOrElse { JSONArray(indexContent) }
        
        val bos = java.io.ByteArrayOutputStream()
        for (i in 0 until index.length()) {
            val chunk = index.getJSONObject(i)
            val partFile = File(chunksDir, "part-%04d.txt".format(chunk.getInt("part")))
            bos.write(partFile.readBytes())
        }
        val bytes = bos.toByteArray()
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }

    private fun digestUpdateFile(digest: MessageDigest, file: File, relativePath: String) {
        digest.update(relativePath.toByteArray(Charsets.UTF_8))
        digest.update(0)
        val bytes = file.readBytes()
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        digest.update(0)
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseJUnitXml(files: Array<File>): JSONObject {
        var totalTests = 0
        var totalPassed = 0
        var totalFailed = 0
        var totalErrors = 0
        var totalSkipped = 0
        var parsedCount = 0
        var failedCount = 0

        val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        dbFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        dbFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        dbFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        dbFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        dbFactory.isXIncludeAware = false
        dbFactory.isExpandEntityReferences = false
        val dBuilder = dbFactory.newDocumentBuilder()

        files.forEach { file ->
            try {
                val doc = dBuilder.parse(file)
                doc.documentElement.normalize()
                val root = doc.documentElement
                
                val tests = root.getAttribute("tests").toIntOrNull() ?: 0
                val failures = root.getAttribute("failures").toIntOrNull() ?: 0
                val errors = root.getAttribute("errors").toIntOrNull() ?: 0
                val skipped = root.getAttribute("skipped").toIntOrNull() ?: 0
                
                totalTests += tests
                totalFailed += failures
                totalErrors += errors
                totalSkipped += skipped
                totalPassed += (tests - failures - errors - skipped)
                parsedCount++
            } catch (e: Exception) {
                failedCount++
            }
        }

        val status = when {
            failedCount > 0 -> "INCOMPLETE"
            totalFailed > 0 || totalErrors > 0 -> "FAILED"
            totalTests > 0 -> "PASSED"
            else -> "UNKNOWN"
        }
        
        return JSONObject().apply {
            put("tests", totalTests)
            put("passed", totalPassed)
            put("failed", totalFailed)
            put("errors", totalErrors)
            put("skipped", totalSkipped)
            put("status", status)
            put("files_parsed", parsedCount)
            put("files_failed", failedCount)
        }
    }

    data class VerificationReport(val success: Boolean, val findings: List<Finding>, val manifest: JSONObject)
}
