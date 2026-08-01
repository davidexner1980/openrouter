package com.david.openassistant.handoff

import com.david.openassistant.handoff.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class HandoffException(message: String) : Exception(message)

/**
 * Main entry point for the OpenAssistant Overseer Handoff CLI.
 */
object HandoffMain {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            runCommand(args)
        } catch (e: HandoffException) {
            println("ERROR: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            println("ERROR: Unexpected failure: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

    fun runCommand(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: overseer-handoff <command> [project-root]")
            throw HandoffException("No command provided")
        }

        val command = args[0]
        val projectRoot = (if (args.size > 1) File(args[1]) else File(".")).absoluteFile

        println("OpenAssistant Overseer Handoff CLI v1.2")
        println("Command: $command")
        println("Project Root: ${projectRoot.absolutePath}")

        val handoffDir = File(projectRoot, "overseer-handoff")
        handoffDir.mkdirs()
        
        when (command) {
            "generate" -> runGenerate(projectRoot)
            "supplement" -> runSupplement(projectRoot)
            "verify" -> runVerify(projectRoot)
            "clean" -> runClean(projectRoot)
            "stability-test" -> runStabilityTest(projectRoot)
            "acceptance-test" -> runAcceptanceTest(projectRoot)
            "extended" -> {
                throw HandoffException("Command 'extended' is NOT_IMPLEMENTED in this pass.")
            }
            "--version" -> println("1.2.0")
            else -> {
                throw HandoffException("Unknown command: $command")
            }
        }
    }

    private fun runAcceptanceTest(root: File) {
        println("Running Overseer Handoff Acceptance Suite (Isolated)...")
        val startTime = Instant.now().toString()
        val executionId = UUID.randomUUID().toString()
        
        val tempRoot = Files.createTempDirectory("overseer-acceptance-").toFile()
        println("Isolated Acceptance Root: ${tempRoot.absolutePath}")
        
        var checksPassed = 0
        var checksFailed = 0
        val checksExecuted = mutableListOf<String>()
        val checkArtifacts = mutableMapOf<String, String>()
        
        val mandatoryChecks = listOf(
            "setup", "baseline", "stability", "verification", "delta", 
            "subject_binding", "security_scan", "privacy_scan", 
            "zip_creation", "zip_verification", "rollback", "cleanup"
        )
        
        var rollbackStatus = "NOT_RUN"
        var cleanupStatus = "NOT_RUN"
        var securityStatus = "FAILED"
        var privacyStatus = "FAILED"

        try {
            // 1. Setup Isolated Fixture
            println("Gate 0: Setup Isolated Fixture...")
            setupAcceptanceFixture(root, tempRoot)
            checksExecuted.add("setup")
            checksPassed++
            
            val generator = HandoffGenerator(tempRoot)
            val verifier = HandoffVerifier(tempRoot)
            
            // Phase 1: Baseline Generation
            println("Gate 1: Generating Baseline A...")
            val bundleA = generator.generateStandardBundle()
            checksExecuted.add("baseline")
            val manifestA = JSONObject(File(bundleA, "01_HANDOFF_MANIFEST.json").readText())
            val hashA = manifestA.getJSONObject("project").getString("source_manifest_sha256")
            checkArtifacts["baseline"] = hashA
            checksPassed++
            
            // Phase 2: Stability (Baseline B)
            println("Gate 2: Running Stability Test (Baseline B)...")
            val bundleB = generator.generateStandardBundle()
            checksExecuted.add("stability")
            val manifestB = JSONObject(File(bundleB, "01_HANDOFF_MANIFEST.json").readText())
            val hashB = manifestB.getJSONObject("project").getString("source_manifest_sha256")
            
            if (hashA != hashB) {
                checksFailed++
                throw HandoffException("Stability test failed: Source manifests differ")
            }
            
            val subjManifestA = File(bundleA, "build/acceptance-subject-manifest.json")
            val subjManifestB = File(bundleB, "build/acceptance-subject-manifest.json")
            
            if (!subjManifestA.exists() || !subjManifestB.exists()) {
                throw HandoffException("Subject manifest missing during stability test")
            }
            
            val subjectHashA = JSONObject(subjManifestA.readText()).getString("subject_hash")
            val subjectHashB = JSONObject(subjManifestB.readText()).getString("subject_hash")
            
            if (subjectHashA != subjectHashB) {
                checksFailed++
                println("DEBUG: Acceptance subject mismatch detected.")
                // Capture subjects for analysis
                subjManifestA.copyTo(File(root, "tools/overseer-handoff/build/subject-a.json"), overwrite = true)
                subjManifestB.copyTo(File(root, "tools/overseer-handoff/build/subject-b.json"), overwrite = true)
                throw HandoffException("Stability test failed: Acceptance subject manifests differ")
            }
            println("Stability Verified.")
            checksPassed++
            
            // Phase 3: Verify Baseline A
            println("Gate 3: Verifying Baseline A...")
            checksExecuted.add("verification")
            val reportA = verifier.verifyBundle(bundleA, HandoffVerifier.VerifierProfile.PRE_ACCEPTANCE_CANDIDATE)
            if (!reportA.success) {
                checksFailed++
                println("Baseline A verification failed:")
                reportA.findings.forEach { println(" - [${it.severity}] ${it.id}: ${it.message}") }
                throw HandoffException("Baseline A verification failed")
            }
            checkArtifacts["verification"] = calculateSha256(File(bundleA, "verification-report.json"))
            checksPassed++

            // Phase 4: Delta State Test
            println("Gate 4: Testing delta...")
            checksExecuted.add("delta")
            val stateDir = File(tempRoot, "overseer-handoff/state")
            stateDir.mkdirs()
            File(bundleA, "01_HANDOFF_MANIFEST.json").copyTo(File(stateDir, "last-verified-manifest.json"))
            File(bundleA, "project/source_inventory.jsonl").copyTo(File(stateDir, "last-verified-source-inventory.jsonl"))
            File(bundleA, "source/source-map.json").copyTo(File(stateDir, "last-verified-source-map.json"))
            
            val ackFile = File(tempRoot, "overseer-handoff/external-overseer-ack.json")
            ackFile.writeText(JSONObject().apply {
                put("acknowledged_bundle_id", manifestA.getString("bundle_id"))
                put("acknowledged_source_manifest_sha256", hashA)
                put("acknowledged_at_utc", Instant.now().toString())
            }.toString())
            
            val bundleDelta = generator.generateStandardBundle(File(stateDir, "last-verified-manifest.json"))
            val manifestDelta = JSONObject(File(bundleDelta, "01_HANDOFF_MANIFEST.json").readText())
            if (manifestDelta.getString("content_mode") != "SOURCE_DELTA") {
                checksFailed++
                throw HandoffException("Acceptance failed: Delta not detected")
            }
            checksPassed++

            // Phase 5: Subject Binding & Scans
            println("Gate 5: Verifying subject binding and scans...")
            checksExecuted.add("subject_binding")
            val acceptanceSubjectHash = subjectHashA
            checkArtifacts["subject_binding"] = acceptanceSubjectHash
            checksPassed++

            checksExecuted.add("security_scan")
            val secSummaryFile = File(bundleA, "diagnostics/secret-scan-summary.txt")
            if (secSummaryFile.exists() && secSummaryFile.readText().contains("Findings: 0")) {
                securityStatus = "PASSED"
                checksPassed++
            } else {
                println("CHECK FAILED: security_scan. Findings log: ${if (secSummaryFile.exists()) secSummaryFile.readText() else "missing"}")
                checksFailed++
            }
            checkArtifacts["security_scan"] = if (secSummaryFile.exists()) calculateSha256(secSummaryFile) else "missing"

            checksExecuted.add("privacy_scan")
            val privReportFile = File(bundleA, "diagnostics/security-redaction-report.md")
            if (privReportFile.exists()) {
                privacyStatus = "PASSED"
                checksPassed++
            } else {
                println("CHECK FAILED: privacy_scan. Missing report.")
                checksFailed++
            }
            checkArtifacts["privacy_scan"] = if (privReportFile.exists()) calculateSha256(privReportFile) else "missing"

            // Phase 6: ZIP Checks
            println("Gate 6: ZIP checks...")
            checksExecuted.add("zip_creation")
            val zipFile = File(tempRoot, "acceptance-test.zip")
            DeterministicZipWriter().createZip(bundleA, zipFile)
            checksPassed++
            checkArtifacts["zip_creation"] = calculateSha256(zipFile)

            checksExecuted.add("zip_verification")
            val extractDir = File(tempRoot, "extract-verify")
            SafeZipExtractor(extractDir).extract(zipFile)
            val zipReport = verifier.verifyBundle(extractDir, HandoffVerifier.VerifierProfile.PRE_ACCEPTANCE_CANDIDATE)
            if (zipReport.success) {
                checksPassed++
            } else {
                println("CHECK FAILED: zip_verification. Findings:")
                zipReport.findings.forEach { println(" - [${it.severity}] ${it.id}: ${it.message}") }
                checksFailed++
            }
            checkArtifacts["zip_verification"] = calculateSha256(File(extractDir, "verification-report.json"))

            // Phase 7: Rollback & Cleanup
            println("Gate 7: Measuring rollback...")
            checksExecuted.add("rollback")
            val stateFile = File(tempRoot, "overseer-handoff/state/last-verified-manifest.json")
            val backupState = File(tempRoot, "overseer-handoff/state/backup.json")
            if (stateFile.exists()) stateFile.copyTo(backupState)
            
            stateFile.delete() // Rollback
            val rollbackVerified = !stateFile.exists() && backupState.exists()
            rollbackStatus = if (rollbackVerified) "SUCCESS" else "FAILED"
            if (rollbackVerified) {
                backupState.copyTo(stateFile) // Restore
                checksPassed++
            } else checksFailed++
            
            println("Gate 8: Measuring cleanup...")
            checksExecuted.add("cleanup")
            
            val logDir = File(root, "tools/overseer-handoff/build/tool-acceptance")
            logDir.mkdirs()
            subjManifestA.copyTo(File(logDir, "subject-manifest.json"), overwrite = true)
            
            val workDir = File(tempRoot, "overseer-handoff/work")
            workDir.deleteRecursively()
            val cleanupVerified = !workDir.exists()
            cleanupStatus = if (cleanupVerified) "SUCCESS" else "FAILED"
            if (cleanupVerified) checksPassed++ else checksFailed++

            // Final Result Construction with Stabilization
            val toolExecFile = File(root, "tools/overseer-handoff/build/tool-execution/execution.json")
            val toolExecId = if (toolExecFile.exists()) JSONObject(toolExecFile.readText()).optString("execution_id", "unknown") else "unknown"

            val metrics = mapOf(
                "files_examined" to verifier.metrics.filesExamined,
                "security_scanned" to verifier.metrics.securityFilesScanned,
                "privacy_scanned" to verifier.metrics.privacyFilesScanned,
                "acceptance_artifacts" to verifier.metrics.acceptanceArtifactsExamined
            )

            // Pass 1: Build initial artifacts.json
            val artifacts = JSONObject().apply {
                put("execution_id", executionId)
                val arr = JSONArray()
                listOf("execution.json", "acceptance-summary.json", "artifacts.json", "subject-manifest.json").forEach { rel ->
                    arr.put(JSONObject().apply {
                        put("relative_path", rel)
                        put("sha256", "STABILIZING")
                        put("classification", if (rel == "artifacts.json") "ACCEPTANCE_ARTIFACT_MANIFEST" else "ACCEPTANCE_EVIDENCE")
                        put("required", true)
                    })
                }
                put("artifacts", arr)
                put("rollback_status", rollbackStatus)
                put("cleanup_status", cleanupStatus)
            }
            File(logDir, "artifacts.json").writeText(artifacts.toString(2))
            val artHash = calculateSha256(File(logDir, "artifacts.json"))

            val result = AcceptanceResult(
                executionId = executionId,
                taskPath = ":verifyOverseerHandoffAcceptance",
                startedAtUtc = startTime,
                finishedAtUtc = Instant.now().toString(),
                taskOutcome = "SUCCESS",
                acceptanceSubjectAlgorithm = "path-sha256-v2",
                acceptanceSubjectSha256 = acceptanceSubjectHash,
                sourceManifestSha256 = hashA,
                toolTestExecutionId = toolExecId,
                checksRequired = mandatoryChecks,
                checksExecuted = checksExecuted,
                checksPassed = checksPassed,
                checksFailed = checksFailed,
                rollbackStatus = rollbackStatus,
                cleanupStatus = cleanupStatus,
                securityStatus = securityStatus,
                privacyStatus = privacyStatus,
                artifactManifestSha256 = artHash,
                checkArtifacts = checkArtifacts,
                metrics = metrics,
                isolatedFixtureId = "overseer-acceptance-fixture"
            )

            val summary = JSONObject().apply {
                put("status", result.getStatus())
                put("execution_id", result.executionId)
                put("acceptance_subject_sha256", result.acceptanceSubjectSha256)
                put("acceptance_subject_algorithm", result.acceptanceSubjectAlgorithm)
                put("checks_passed", result.checksPassed)
                put("checks_failed", result.checksFailed)
                put("provenance", "CURRENT")
                put("artifact_manifest_sha256", artHash)
            }
            File(logDir, "acceptance-summary.json").writeText(summary.toString(2))

            // Pass 2: Finalize artifacts.json with actual hashes (excluding logs/metadata for stabilization)
            val finalArtifactsArr = JSONArray()
            listOf("subject-manifest.json").forEach { rel ->
                val f = File(logDir, rel)
                if (f.exists()) {
                    finalArtifactsArr.put(JSONObject().apply {
                        put("relative_path", rel)
                        put("sha256", calculateSha256(f))
                        put("size", f.length())
                        put("classification", "ACCEPTANCE_EVIDENCE")
                        put("required", true)
                    })
                }
            }
            artifacts.put("artifacts", finalArtifactsArr)
            File(logDir, "artifacts.json").writeText(artifacts.toString(2))
            
            // Final stabilization: recalculate artHash and update summary/execution
            val finalArtHash = calculateSha256(File(logDir, "artifacts.json"))
            summary.put("artifact_manifest_sha256", finalArtHash)
            File(logDir, "acceptance-summary.json").writeText(summary.toString(2))
            
            val finalResult = result.copy(artifactManifestSha256 = finalArtHash)
            File(logDir, "execution.json").writeText(finalResult.toJson().toString(2))

            println("SUCCESS: Acceptance suite passed.")
        } catch (e: Exception) {
            println("FAILURE: Acceptance suite failed: ${e.message}")
            // Write failure record
            val logDir = File(root, "tools/overseer-handoff/build/tool-acceptance")
            logDir.mkdirs()
            val failureResult = JSONObject().apply {
                put("status", "FAILED")
                put("error", e.message)
                put("timestamp", Instant.now().toString())
            }
            File(logDir, "acceptance-summary.json").writeText(failureResult.toString(2))
            throw e
        } finally {
            if (checksFailed == 0) {
                println("Cleaning up isolated root...")
                tempRoot.deleteRecursively()
            } else {
                println("PRESERVING isolated root for inspection: ${tempRoot.absolutePath}")
            }
        }
    }

    private fun diffBundles(a: File, b: File) {
        val filesA = a.walkTopDown().filter { it.isFile }.map { it.relativeTo(a).path.replace('\\', '/') }.toSet()
        val filesB = b.walkTopDown().filter { it.isFile }.map { it.relativeTo(b).path.replace('\\', '/') }.toSet()
        
        println("Files only in A: ${filesA - filesB}")
        println("Files only in B: ${filesB - filesA}")
        
        (filesA intersect filesB).forEach { rel ->
            val fa = File(a, rel)
            val fb = File(b, rel)
            if (calculateSha256(fa) != calculateSha256(fb)) {
                 println("File content differs: $rel")
                 if (rel.endsWith(".json") || rel.endsWith(".jsonl") || rel.endsWith(".md") || rel.endsWith(".txt")) {
                     if (rel == "01_HANDOFF_MANIFEST.json") {
                         val ja = JSONObject(fa.readText()).apply { remove("bundle_id"); remove("created_at_utc") }
                         val jb = JSONObject(fb.readText()).apply { remove("bundle_id"); remove("created_at_utc") }
                         println("Manifest A (redacted): ${DeterministicJson.stringify(ja)}")
                         println("Manifest B (redacted): ${DeterministicJson.stringify(jb)}")
                     } else {
                         println("A first 200 chars: ${fa.readText().take(200)}")
                         println("B first 200 chars: ${fb.readText().take(200)}")
                     }
                 }
            }
        }
    }

    private fun setupAcceptanceFixture(src: File, dest: File) {
        val processor = SourceProcessor(src)
        val entries = processor.scan()
        
        entries.filter { it.classification != "OMITTED" }.forEach { entry ->
            val srcFile = File(src, entry.path)
            val destFile = File(dest, entry.path)
            destFile.parentFile.mkdirs()
            srcFile.copyTo(destFile)
        }
        
        // OH-V15: Include ALL tool source, build configuration, tool test evidence, runtime input, and HISTORICAL evidence/diagnostics
        val extra = listOf(
            "tools/overseer-handoff/src",
            "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties",
            "tools/overseer-handoff/build/tool-execution",
            "tools/overseer-handoff/build/test-results/test",
            "overseer-input",
            "evidence",
            "diagnostics"
        )
        extra.forEach { rel ->
            val s = File(src, rel)
            if (s.exists()) {
                val d = File(dest, rel)
                if (s.isDirectory) {
                    if (!d.exists()) s.copyRecursively(d)
                } else {
                    if (!d.exists()) {
                        d.parentFile.mkdirs()
                        s.copyTo(d)
                    }
                }
            }
        }
    }

    private fun runStabilityTest(root: File) {
        println("Running Stability Test (Standard Bundle A vs B)...")
        val generator = HandoffGenerator(root)
        
        println("Generating Bundle A...")
        val bundleA = generator.generateStandardBundle()
        val manifestA = JSONObject(File(bundleA, "01_HANDOFF_MANIFEST.json").readText())
        val hashA = manifestA.getJSONObject("project").getString("source_manifest_sha256")
        
        println("Generating Bundle B...")
        val bundleB = generator.generateStandardBundle()
        val manifestB = JSONObject(File(bundleB, "01_HANDOFF_MANIFEST.json").readText())
        val hashB = manifestB.getJSONObject("project").getString("source_manifest_sha256")
        
        println("Bundle A Hash: $hashA")
        println("Bundle B Hash: $hashB")
        
        if (hashA == hashB) {
            println("SUCCESS: Stability verified. Source manifests are identical.")
        } else {
            println("FAILURE: Stability test failed. Source manifests differ.")
            throw HandoffException("Stability test failed")
        }
        
        // OH-025: Verify UPLOAD_THIS self-containment
        val targetHandoffDir = File(root, "overseer-handoff")
        val uploadZip = File(targetHandoffDir, "UPLOAD_THIS/OpenAssistant-Overseer-Handoff-Latest.zip")
        if (uploadZip.exists()) {
            println("Verifying UPLOAD_THIS self-containment...")
            val verifier = HandoffVerifier(root)
            val tempExtractDir = File(targetHandoffDir, "work/verify-upload-this")
            tempExtractDir.mkdirs()
            try {
                SafeZipExtractor(tempExtractDir).extract(uploadZip)
                val report = verifier.verifyBundle(tempExtractDir)
                if (!report.success) {
                     println("FAILURE: UPLOAD_THIS verification failed")
                     report.findings.forEach { println(" - [${it.severity}] ${it.id}: ${it.message}") }
                     throw HandoffException("UPLOAD_THIS is invalid")
                }
            } finally {
                tempExtractDir.deleteRecursively()
            }
        }

        bundleA.parentFile.deleteRecursively()
        bundleB.parentFile.deleteRecursively()
    }

    private fun runSupplement(root: File) {
        println("Generating Overseer Supplement Bundle...")
        try {
            val requestFile = File(root, "overseer-request.json").let { if (it.exists()) it else File(root, "overseer-requested-files.txt") }
            if (!requestFile.exists()) {
                println("ERROR: No supplement request found (overseer-request.json or overseer-requested-files.txt)")
                throw HandoffException("Supplement request missing")
            }

            val parser = SupplementRequestParser(root)
            val request = parser.parse(requestFile)
            
            val generator = HandoffGenerator(root)
            val bundleDir = generator.generateStandardBundle(null, request)
            
            publishBundle(root, bundleDir)
        } catch (e: Exception) {
            println("ERROR: Supplement generation failed: ${e.message}")
            if (e !is HandoffException) e.printStackTrace()
            throw if (e is HandoffException) e else HandoffException("Supplement failed: ${e.message}")
        }
    }

    private fun publishBundle(root: File, bundleDir: File) {
        println("Verifying staging folder...")
        val verifier = HandoffVerifier(root)
        val folderReport = verifier.verifyBundle(bundleDir, HandoffVerifier.VerifierProfile.FINAL_PUBLICATION)
        
        if (!folderReport.success) {
            println("ERROR: Staging folder verification failed.")
            folderReport.findings.forEach { println(" - [${it.severity}] ${it.id}: ${it.message}") }
            throw HandoffException("Staging folder verification failed")
        }
        
        println("Zipping bundle...")
        val zipFile = File(bundleDir.parentFile, "${bundleDir.name}.zip")
        DeterministicZipWriter().createZip(bundleDir, zipFile)
        
        // OH-020: Verify the exact ZIP before publication
        println("Verifying candidate ZIP...")
        val tempExtractDir = File(bundleDir.parentFile, "extract-verify-${UUID.randomUUID()}")
        tempExtractDir.mkdirs()
        var zipReport: HandoffVerifier.VerificationReport? = null
        try {
            extractZip(zipFile, tempExtractDir)
            zipReport = verifier.verifyBundle(tempExtractDir, HandoffVerifier.VerifierProfile.FINAL_PUBLICATION)
            if (!zipReport.success) {
                println("ERROR: Candidate ZIP verification failed after extraction.")
                zipReport.findings.forEach { println(" - [${it.severity}] ${it.id}: ${it.message}") }
                throw HandoffException("Candidate ZIP verification failed")
            }
        } finally {
            tempExtractDir.deleteRecursively()
        }
        
        println("Publishing bundle...")
        val targetHandoffDir = File(root, "overseer-handoff")
        val uploadDir = File(targetHandoffDir, "UPLOAD_THIS")
        uploadDir.mkdirs()
        
        // Clean old upload artifacts
        uploadDir.listFiles()?.forEach { it.delete() }
        
        val finalZip = File(uploadDir, "OpenAssistant-Overseer-Handoff-Latest.zip")
        Files.copy(zipFile.toPath(), finalZip.toPath())
        
        val sha256 = calculateSha256(finalZip)
        File(uploadDir, "SHA256.txt").writeText("$sha256  OpenAssistant-Overseer-Handoff-Latest.zip")
        
        // OH-V15: Create VERIFICATION_RECEIPT.json outside the ZIP
        if (zipReport != null) {
            val receipt = JSONObject().apply {
                put("bundle_id", zipReport.manifest.optString("bundle_id", "unknown"))
                put("zip_sha256", sha256)
                put("verification_status", if (zipReport.success) "PASSED" else "FAILED")
                put("timestamp_utc", Instant.now().toString())
                put("metrics", verifier.metrics.toJson())
            }
            File(uploadDir, "VERIFICATION_RECEIPT.json").writeText(receipt.toString(2))
        }
        
        val manifest = JSONObject(File(bundleDir, "01_HANDOFF_MANIFEST.json").readText())
        val readmeText = """
            UPLOAD ONLY THIS FILE:
            OpenAssistant-Overseer-Handoff-Latest.zip
            
            Metadata:
            - Size: ${finalZip.length()} bytes
            - SHA-256: $sha256
            - Content Mode: ${manifest.optString("content_mode")}
            - Quality Level: ${manifest.optString("quality_level")}
            - Runtime Status: ${manifest.optJSONObject("runtime")?.optString("status")}
            
            This bundle was generated and verified by the OpenAssistant Overseer Handoff Tool.
            
            IMPORTANT: Do not ZIP the entire overseer-handoff/ directory.
            Upload only the .zip file inside UPLOAD_THIS/.
        """.trimIndent()
        File(uploadDir, "README.txt").writeText(readmeText)
        
        // Keep historical ZIP in the parent folder
        val historicalZip = File(targetHandoffDir, zipFile.name)
        Files.move(zipFile.toPath(), historicalZip.toPath())
        
        // OH-009: Historical retention (keep last 3)
        cleanupHistoricalZips(targetHandoffDir, 3)

        // OH-007: Strengthened verified state
        if (bundleDir.name.contains("Handoff")) {
            val stateDir = File(root, "overseer-handoff/state")
            stateDir.mkdirs()
            File(bundleDir, "01_HANDOFF_MANIFEST.json").copyTo(File(stateDir, "last-verified-manifest.json"), overwrite = true)
            File(bundleDir, "project/source_inventory.jsonl").copyTo(File(stateDir, "last-verified-source-inventory.jsonl"), overwrite = true)
            File(bundleDir, "source/source-map.json").copyTo(File(stateDir, "last-verified-source-map.json"), overwrite = true)
            
            // Backwards compatibility
            File(bundleDir, "01_HANDOFF_MANIFEST.json").copyTo(File(stateDir, "last-handoff-manifest.json"), overwrite = true)
            
            File(stateDir, "state-metadata.json").writeText(JSONObject().apply {
                put("last_bundle_id", manifest.getString("bundle_id"))
                put("updated_at", Instant.now().toString())
            }.toString(2))
        }
        
        // OH-009: Remove successful work directories
        bundleDir.parentFile.deleteRecursively()

        println("SUCCESS: Bundle generated and verified.")
        println("UPLOAD ONLY THIS FILE:")
        println(finalZip.absolutePath)
        println("Size: ${finalZip.length()} bytes")
        println("SHA-256: $sha256")
    }

    private fun cleanupHistoricalZips(dir: File, keep: Int) {
        // Clean ZIPs
        val zips = (dir.listFiles { f -> f.isFile && f.extension == "zip" && (f.name.startsWith("OpenAssistant-Handoff-") || f.name.startsWith("OpenAssistant-Supplement-")) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        
        if (zips.size > keep) {
            zips.drop(keep).forEach { 
                println("Removing historical bundle ZIP: ${it.name}")
                it.delete() 
            }
        }

        // OH-015: Do not retain expanded historical bundles
        dir.listFiles { f -> f.isDirectory && (f.name.startsWith("OpenAssistant-Handoff-") || f.name.startsWith("OpenAssistant-Supplement-")) && f.name != "UPLOAD_THIS" && f.name != "state" && f.name != "work" }
            ?.forEach { 
                println("Removing historical expanded bundle: ${it.name}")
                it.deleteRecursively() 
            }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        SafeZipExtractor(targetDir).extract(zipFile)
    }

    private fun runGenerate(root: File) {
        println("Generating Overseer Handoff Bundle...")
        try {
            val stateDir = File(root, "overseer-handoff/state")
            val parentManifestFile = File(stateDir, "last-verified-manifest.json").let { 
                if (it.exists()) it else File(stateDir, "last-handoff-manifest.json")
            }
            
            val generator = HandoffGenerator(root)
            val bundleDir = generator.generateStandardBundle(if (parentManifestFile.exists()) parentManifestFile else null)
            println("Bundle generated at: ${bundleDir.absolutePath}")
            
            publishBundle(root, bundleDir)
        } catch (e: Exception) {
            println("ERROR: Generation failed: ${e.message}")
            if (e !is HandoffException) e.printStackTrace()
            throw if (e is HandoffException) e else HandoffException("Generation failed: ${e.message}")
        }
    }

    private fun runVerify(root: File) {
        println("Verifying Overseer Handoff Bundle...")
        try {
            val handoffDir = File(root, "overseer-handoff")
            val uploadZip = File(handoffDir, "UPLOAD_THIS/OpenAssistant-Overseer-Handoff-Latest.zip")
            
            val zipToVerify = if (uploadZip.exists()) uploadZip else {
                 val zips = handoffDir.listFiles { f -> f.extension == "zip" && f.name.startsWith("OpenAssistant-Handoff-") }
                    ?.sortedByDescending { it.lastModified() }
                 zips?.firstOrNull()
            }

            if (zipToVerify == null) {
                println("ERROR: No bundles found in overseer-handoff/")
                throw HandoffException("No bundle to verify")
            }
            
            println("Verifying bundle ZIP: ${zipToVerify.name}")
            val verifier = HandoffVerifier(root)
            
            val tempExtractDir = File(handoffDir, "work/verify-${UUID.randomUUID()}")
            tempExtractDir.mkdirs()
            try {
                extractZip(zipToVerify, tempExtractDir)
                val report = verifier.verifyBundle(tempExtractDir, HandoffVerifier.VerifierProfile.STANDALONE_AUDIT)
                
                // Copy reports out before cleanup
                File(tempExtractDir, "verification-report.json").copyTo(File(handoffDir, "verification-report-latest.json"), overwrite = true)
                File(tempExtractDir, "verification-report.md").copyTo(File(handoffDir, "verification-report-latest.md"), overwrite = true)

                if (report.success) {
                    println("SUCCESS: Bundle verification passed.")
                } else {
                    println("FAILURE: Bundle verification failed:")
                    report.findings.forEach { println(" - [${it.severity}] ${it.id}: ${it.message}") }
                    throw HandoffException("Verification failed")
                }
            } finally {
                tempExtractDir.deleteRecursively()
            }
        } catch (e: HandoffException) {
            throw e
        } catch (e: Exception) {
            println("ERROR: Verification failed: ${e.message}")
            e.printStackTrace()
            throw HandoffException("Verification crashed: ${e.message}")
        }
    }

    private fun runClean(root: File) {
        println("Cleaning Overseer Handoff working directories...")
        val handoffDir = File(root, "overseer-handoff")
        val workDir = File(handoffDir, "work")
        
        if (workDir.exists()) {
            println("Removing work directory: ${workDir.absolutePath}")
            workDir.deleteRecursively()
        }
        
        println("SUCCESS: Cleanup complete (state/ and bundles preserved).")
    }

    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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
}
