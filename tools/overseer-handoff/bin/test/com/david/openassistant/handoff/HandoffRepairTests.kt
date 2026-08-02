package com.david.openassistant.handoff

import com.david.openassistant.handoff.model.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets

class HandoffRepairTests {

    @Test
    fun testOH003_JUnitAuthoritativeParsing() {
        val tempDir = Files.createTempDirectory("junit-test").toFile()
        val xmlFile = File(tempDir, "TEST-com.example.MyTest.xml")
        xmlFile.writeText("""
            <testsuite name="com.example.MyTest" tests="10" failures="1" errors="0" skipped="0">
                <testcase name="test1" />
            </testsuite>
        """.trimIndent())
        
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val evidenceDir = File(projectRoot, "evidence/jvm-full")
        evidenceDir.mkdirs()
        xmlFile.copyTo(File(evidenceDir, xmlFile.name))
        
        val collector = EvidenceCollector(projectRoot, "dummy-hash")
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        val result = collector.collectBuildEvidence(bundleDir)
        
        val junit = result.getJSONObject("unit_tests")
        assertEquals("FAILED", junit.getString("status"))
        val summary = junit.getJSONObject("summary")
        assertEquals(10, summary.getInt("tests"))
        assertEquals(1, summary.getInt("failed"))
        assertEquals(9, summary.getInt("passed"))
    }

    @Test
    fun testOH004_ApkIdentityJsonAndBom() {
        val tempDir = Files.createTempDirectory("apk-id-test").toFile()
        val proofFile = File(tempDir, "identity-proof.json")
        val json = JSONObject().apply {
            put("package_name", "com.david.openassistant")
            put("version_name", "1.8.33")
            put("version_code", 53)
            put("local_hash", "abcd")
            put("installed_hash", "abcd")
        }
        
        // Write with UTF-16LE BOM
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_16LE)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        proofFile.writeBytes(bom + bytes)
        
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val evidenceDir = File(projectRoot, "evidence/apk-identity")
        evidenceDir.mkdirs()
        proofFile.copyTo(File(evidenceDir, proofFile.name))
        
        val collector = EvidenceCollector(projectRoot, "dummy-hash")
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        val result = collector.collectBuildEvidence(bundleDir)
        
        val apkId = result.getJSONObject("apk_identity")
        assertEquals("VERIFIED", apkId.getString("status"))
    }

    @Test
    fun testOH007_SecurityScannerFalsePositives() {
        val scanner = SecurityScanner()
        
        val safeText = "password=\"false\" and Bearer [REDACTED]"
        val (redacted, result) = scanner.redact(safeText, ScannerScope.SOURCE_CODE)
        
        assertEquals(safeText, redacted)
        assertEquals(0, result.unresolvedProbableSecrets)
    }

    @Test
    fun testOH008_HiddenReasoningRedactionOnlyInEvidence() {
        val scanner = SecurityScanner()
        
        val content = """{"reasoning": "I am thinking", "other": "value"}"""
        
        // In source code, it should NOT be redacted
        val (sourceRedacted, sourceResult) = scanner.redact(content, ScannerScope.SOURCE_CODE)
        assertEquals(content, sourceRedacted)
        assertEquals(0, sourceResult.reasoningFieldsRemoved)
        
        // In evidence, it SHOULD be redacted
        val (evRedacted, evResult) = scanner.redact(content, ScannerScope.RUNTIME_PROVIDER_DATA)
        assertTrue(evRedacted.contains("[INTERNAL_REASONING_REDACTED]"))
        assertEquals(1, evResult.reasoningFieldsRemoved)
    }

    @Test
    fun testOH013_StableSourceHash() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val processor = SourceProcessor(projectRoot)
        
        val entries = listOf(
            SourceFileEntry("src/main/A.kt", 100, 10, "sha1", "PRODUCTION_SOURCE", true, false, true, false),
            SourceFileEntry("src/main/B.kt", 200, 20, "sha2", "PRODUCTION_SOURCE", true, false, true, false)
        )
        
        val hash1 = processor.calculateSourceManifestHash(entries)
        
        // Different order should yield same hash
        val hash2 = processor.calculateSourceManifestHash(entries.reversed())
        assertEquals(hash1, hash2)
        
        // Adding OMITTED entry should not change hash
        val entriesWithOmitted = entries + SourceFileEntry("build/C.kt", 50, 5, "sha3", "OMITTED", false, false, false, false)
        val hash3 = processor.calculateSourceManifestHash(entriesWithOmitted)
        assertEquals(hash1, hash3)
    }

    @Test
    fun testP0_NoFabricatedRuntimeEvidence() {
        val fabricatedValue = "sess_20260727_001"
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val exporter = RuntimePacketExporter(projectRoot)
        val targetDir = Files.createTempDirectory("runtime-target").toFile()
        
        // When no packet.zip exists, it should be UNAVAILABLE
        val report = exporter.export(targetDir)
        assertEquals("UNAVAILABLE", report.getString("status"))
        assertFalse(report.toString().contains(fabricatedValue))
    }

    @Test
    fun testOH005_SourceByteIntegrity() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val sourceFile = File(projectRoot, "src/main/Main.kt")
        sourceFile.parentFile.mkdirs()
        val originalContent = "package com.example\n\nfun main() { println(\"Hello\") }"
        sourceFile.writeText(originalContent)
        
        val generator = HandoffGenerator(projectRoot)
        val bundleDir = generator.generateStandardBundle()
        
        val bundledFile = File(bundleDir, "source/changed-files/src/main/Main.kt")
        assertTrue(bundledFile.exists())
        assertEquals(originalContent, bundledFile.readText())
    }

    @Test
    fun testOH016_NestedHandoffExclusion() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val nestedHandoff = File(projectRoot, "overseer-handoff/OpenAssistant-Handoff-1.2.3")
        nestedHandoff.mkdirs()
        File(nestedHandoff, "some-file.kt").writeText("should be excluded")
        
        val processor = SourceProcessor(projectRoot)
        val entries = processor.scan()
        
        val included = entries.filter { it.classification != "OMITTED" }
        assertFalse(included.any { it.path.contains("OpenAssistant-Handoff-1.2.3") })
    }

    @Test
    fun testOH016_ToolSourceIncluded() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val toolSource = File(projectRoot, "tools/overseer-handoff/src/main/kotlin/Main.kt")
        toolSource.parentFile.mkdirs()
        toolSource.writeText("package com.example\nfun main() {}")
        
        val processor = SourceProcessor(projectRoot)
        val entries = processor.scan()
        
        val mainEntry = entries.find { it.path == "tools/overseer-handoff/src/main/kotlin/Main.kt" }
        assertTrue(mainEntry != null, "Tool source should be included")
        assertEquals("PRODUCTION_SOURCE", mainEntry.classification)
    }

    @Test
    fun testOH006_MonitorReportClassifiedAsEvidence() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val report = File(projectRoot, "OPENASSISTANT_RESEARCH_MONITOR_REPORT.md")
        report.writeText("# Report")
        
        val processor = SourceProcessor(projectRoot)
        assertEquals("RUNTIME_EVIDENCE", processor.classify(report, report.name))
    }

    @Test
    fun testOH014_BaselineParentManifestIsNull() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val generator = HandoffGenerator(projectRoot)
        val bundleDir = generator.generateStandardBundle(null)
        val manifest = JSONObject(File(bundleDir, "01_HANDOFF_MANIFEST.json").readText())
        
        assertTrue(manifest.isNull("parent_bundle_id"))
        assertTrue(manifest.isNull("parent_snapshot"))
        assertEquals("SOURCE_BASELINE", manifest.getString("content_mode"))
    }

    @Test
    fun testOH008_RequiredFileOmissionsFailVerification() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val verifier = HandoffVerifier(projectRoot)
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        
        // Empty bundle should fail due to missing manifest or required files
        val report = verifier.verifyBundle(bundleDir)
        assertFalse(report.success)
        assertTrue(report.findings.any { it.id == "MISSING_MANIFEST" || it.id == "MISSING_REQUIRED_FILE" })
    }

    @Test
    fun testP0_EmptyRuntimePacketCannotBeFinalSettled() {
        val projectRoot = Files.createTempDirectory("proj-root").toFile()
        val verifier = HandoffVerifier(projectRoot)
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        
        // Create a manifest with impossible runtime combination
        val manifest = JSONObject().apply {
            put("bundle_id", "test-bundle")
            put("content_mode", "SOURCE_BASELINE")
            put("schema_version", 2)
            put("project", JSONObject().apply {
                put("is_git", false)
                put("source_manifest_sha256", "dummy")
            })
            put("runtime", JSONObject().apply {
                put("status", "FINAL_SETTLED")
                put("monitor_session_ids", JSONArray()) // Impossible: FINAL_SETTLED but no sessions
            })
            put("files", JSONObject().apply {
                put("included", 0)
                put("chunked", 0)
                put("omitted", 0)
            })
            put("verification", JSONObject().apply {
                put("tool_tests", JSONObject().apply {
                    put("provenance", "CURRENT")
                })
            })
        }
        File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(manifest.toString())
        File(bundleDir, "project").mkdirs()
        File(bundleDir, "project/git_status.txt").writeText("git_status = NOT_A_GIT_REPOSITORY")
        File(bundleDir, "runtime").mkdirs()
        File(bundleDir, "runtime/packet_verification_report.json").writeText(JSONObject().put("status", "OK").toString())
        
        listOf(
            "00_READ_ME_FIRST.md", "02_EVIDENCE_INDEX.json",
            "03_CURRENT_STATUS.md", "04_MISSING_OR_UNVERIFIED.md", "05_ROLLBACK.md",
            "project/project_identity.json", "project/source_inventory.jsonl", "project/source_hashes.sha256",
            "project/source_manifest_sha256", "evidence/evidence_hashes.sha256", "evidence/evidence_manifest_sha256",
            "evidence/evidence_inventory.jsonl", "project/environment.txt",
            "project/git_branch.txt", "project/git_status.txt", "project/critical_source_integrity.json", "source/source-map.json",
            "source/omitted-files.jsonl", "diagnostics/defect-ledger.jsonl", "diagnostics/security-redaction-report.md",
            "diagnostics/secret-scan-summary.txt", "diagnostics/warning-ledger.jsonl",
            "build/tool-tests/summary.json", "build/tool-tests/execution.json",
            "build/command-index.json", "runtime/omitted-runtime-data.json"
        ).forEach { path ->
            File(bundleDir, path).apply { 
                parentFile.mkdirs()
                if (path.endsWith(".jsonl")) writeText("") else if (path.endsWith(".md") || path.endsWith(".txt")) writeText("Line 1\nLine 2\nLine 3\n") else writeText("{}") 
            }
        }

        val report = verifier.verifyBundle(bundleDir)
        assertTrue(report.findings.any { it.id == "IMPOSSIBLE_RUNTIME" })
    }

    @Test
    fun testV14_AntiFabrication_StatusIsDerived() {
        val result = AcceptanceResult(
            executionId = "test-id",
            taskPath = ":test",
            startedAtUtc = "start",
            finishedAtUtc = "end",
            taskOutcome = "SUCCESS",
            acceptanceSubjectAlgorithm = "alg",
            acceptanceSubjectSha256 = "hash",
            sourceManifestSha256 = "hash",
            toolTestExecutionId = "tool-id",
            checksRequired = listOf("a", "b"),
            checksExecuted = listOf("a"),
            checksPassed = 1,
            checksFailed = 0,
            rollbackStatus = "SUCCESS",
            cleanupStatus = "SUCCESS",
            securityStatus = "PASSED",
            privacyStatus = "PASSED",
            artifactManifestSha256 = "art-hash"
        )
        // Incomplete checks should result in FAILED status
        assertEquals("FAILED", result.getStatus(), "Status must be FAILED if mandatory checks are incomplete")
        
        val completeResult = result.copy(
            checksExecuted = listOf("a", "b"), 
            checksPassed = 2,
            metrics = mapOf("files_examined" to 10)
        )
        assertEquals("PASSED", completeResult.getStatus(), "Status should be PASSED only when all requirements met")
    }

    @Test
    fun testV14_VerifierProfiles() {
        val verifier = HandoffVerifier(File("."))
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(JSONObject().apply {
            put("bundle_id", "test")
            put("schema_version", 2)
            put("content_mode", "SOURCE_BASELINE")
            put("project", JSONObject().put("application_id", "test").put("source_manifest_sha256", "dummy").put("evidence_manifest_sha256", "dummy"))
            put("runtime", JSONObject().put("status", "UNAVAILABLE"))
            put("verification", JSONObject())
        }.toString())
        
        // PRE_ACCEPTANCE_CANDIDATE should not fail just because acceptance is missing
        val reportA = verifier.verifyBundle(bundleDir, HandoffVerifier.VerifierProfile.PRE_ACCEPTANCE_CANDIDATE)
        assertFalse(reportA.findings.any { it.id == "MISSING_ACCEPTANCE_EVIDENCE" }, "PRE_ACCEPTANCE should allow missing acceptance evidence")
        
        // FINAL_PUBLICATION must fail if acceptance is missing
        val reportB = verifier.verifyBundle(bundleDir, HandoffVerifier.VerifierProfile.FINAL_PUBLICATION)
        assertTrue(reportB.findings.any { it.id == "MISSING_ACCEPTANCE_EVIDENCE" }, "FINAL_PUBLICATION must require acceptance evidence")
    }

    @Test
    fun testV14_TruthfulMetrics() {
        val verifier = HandoffVerifier(File("."))
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(JSONObject().apply {
            put("bundle_id", "test")
            put("schema_version", 2)
            put("project", JSONObject().put("application_id", "test").put("source_manifest_sha256", "dummy").put("evidence_manifest_sha256", "dummy"))
            put("runtime", JSONObject().put("status", "UNAVAILABLE"))
            put("verification", JSONObject())
        }.toString())
        
        val report = verifier.verifyBundle(bundleDir, HandoffVerifier.VerifierProfile.PRE_ACCEPTANCE_CANDIDATE)
        
        val reportFile = File(bundleDir, "verification-report.json")
        assertTrue(reportFile.exists(), "verification-report.json should be generated")
        val reportJson = JSONObject(reportFile.readText())
        val metrics = reportJson.optJSONObject("metrics") ?: JSONObject()
        
        // At minimum, manifest and required files should have been examined
        assertTrue(metrics.optInt("files_examined", 0) > 0, "metrics: files_examined should be > 0")
        assertTrue(metrics.optInt("required_files_examined", 0) > 0, "metrics: required_files_examined should be > 0")
    }

    @Test
    fun testV13_WindowsPathNormalization() {
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        val buildDir = File(bundleDir, "build/some/path")
        buildDir.mkdirs()
        val logFile = File(buildDir, "test.log")
        // Absolute path pattern detection
        logFile.writeText("Private path: C:\\Users\\david\\secret")
        
        val verifier = HandoffVerifier(File("."))
        
        // verifyBundle should trigger scan
        // We need 01_HANDOFF_MANIFEST.json and others to avoid error-out early
        File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(JSONObject().apply {
            put("bundle_id", "test")
            put("schema_version", 2)
            put("project", JSONObject().put("application_id", "test").put("source_manifest_sha256", "dummy"))
            put("runtime", JSONObject().put("status", "UNAVAILABLE"))
            put("verification", JSONObject())
        }.toString())
        
        val report = verifier.verifyBundle(bundleDir)
        // If normalization works, it should classify build/test.log as GENERATED_EVIDENCE and find the PRIVACY_LEAK
        assertTrue(report.findings.any { it.id == "PRIVACY_LEAK" }, "Privacy leak should be detected in log file inside build dir (using normalized path for scope)")
    }

    @Test
    fun testV13_FailClosedOnCrash() {
        val verifier = HandoffVerifier(File("."))
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        // No manifest will trigger a Finding but not a crash. 
        // We want to test the try-catch in runCheck.
        
        // We can't easily force a crash in a private block without reflection or a mock, 
        // but we can test that it handles invalid state.
        
        val report = verifier.verifyBundle(bundleDir)
        assertFalse(report.success)
    }

    @Test
    fun testV13_AcceptanceSubjectStability() {
        val bundleDir = Files.createTempDirectory("bundle-dir").toFile()
        File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(JSONObject().apply {
            put("bundle_id", "id1")
            put("created_at_utc", "time1")
            put("project", JSONObject().put("source_manifest_sha256", "hash"))
        }.toString())
        File(bundleDir, "source/changed-files/tools/overseer-handoff").mkdirs()
        val toolFile = File(bundleDir, "source/changed-files/tools/overseer-handoff/A.kt")
        toolFile.writeText("content")
        
        val processor = SourceProcessor(File("."))
        val hash1 = processor.calculateAcceptanceSubjectManifestHash(bundleDir)
        
        // Change bundle_id and timestamp
        File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(JSONObject().apply {
            put("bundle_id", "id2")
            put("created_at_utc", "time2")
            put("project", JSONObject().put("source_manifest_sha256", "hash"))
        }.toString())
        
        val hash2 = processor.calculateAcceptanceSubjectManifestHash(bundleDir)
        assertEquals(hash1, hash2, "Acceptance subject hash must be stable across bundle_id/timestamp changes")
        
        // Change content should change hash
        toolFile.writeText("content-changed")
        val hash3 = processor.calculateAcceptanceSubjectManifestHash(bundleDir)
        assertTrue(hash1 != hash3)
    }
}
