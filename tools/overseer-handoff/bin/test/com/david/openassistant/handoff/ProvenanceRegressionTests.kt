package com.david.openassistant.handoff

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvenanceRegressionTests {

    @Test
    fun testCollectorCannotSynthesizeMetadata() {
        val root = File("temp-metadata-test-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val collector = EvidenceCollector(root, "dummy-hash")
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            
            val buildEvidence = collector.collectBuildEvidence(bundleDir)
            val toolTests = buildEvidence.getJSONObject("tool_tests")
            
            // Should be REPORT_ONLY because execution.json is missing in the fake root
            assertEquals("REPORT_ONLY", toolTests.getString("provenance"))
            assertFalse(toolTests.has("exit_code"), "Should not have synthesized exit_code")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testEvidenceClaimValidation() {
        val root = File("temp-claim-test-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val verifier = HandoffVerifier(root)
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            
            val index = JSONObject().apply {
                put("bundle_id", "test-bundle")
                put("claims", JSONArray().apply {
                    put(JSONObject().apply {
                        put("claim_id", "TEST-001")
                        put("claim", "Test claim")
                        put("status", "VERIFIED")
                        put("evidence", JSONArray().apply {
                            put(JSONObject().apply {
                                put("path", "non-existent.txt")
                                put("sha256", "dummy")
                            })
                        })
                    })
                })
            }
            File(bundleDir, "02_EVIDENCE_INDEX.json").writeText(index.toString())
            
            val projectDir = File(bundleDir, "project")
            projectDir.mkdirs()
            File(projectDir, "git_status.txt").writeText("git_status = NOT_A_GIT_REPOSITORY")
            
            val manifest = JSONObject().apply {
                put("bundle_id", "test-bundle")
                put("schema_version", 2)
                put("content_mode", "SOURCE_BASELINE")
                put("project", JSONObject().apply { put("is_git", false) })
            }
            File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(manifest.toString())
            
            listOf("00_READ_ME_FIRST.md", "03_CURRENT_STATUS.md", "04_MISSING_OR_UNVERIFIED.md", "05_ROLLBACK.md").forEach { name ->
                File(bundleDir, name).writeText("Line 1\nLine 2\nLine 3\n")
            }

            val report = verifier.verifyBundle(bundleDir)
            assertFalse(report.success, "Verification should fail for missing evidence path")
            assertTrue(report.findings.any { it.id == "MISSING_EVIDENCE_PATH" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testDirectoryEvidenceRejection() {
        val root = File("temp-dir-test-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val verifier = HandoffVerifier(root)
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            val dummyDir = File(bundleDir, "dummy-dir")
            dummyDir.mkdirs()
            
            val index = JSONObject().apply {
                put("bundle_id", "test-bundle")
                put("claims", JSONArray().apply {
                    put(JSONObject().apply {
                        put("claim_id", "TEST-002")
                        put("claim", "Test claim")
                        put("status", "VERIFIED")
                        put("evidence", JSONArray().apply {
                            put(JSONObject().apply {
                                put("path", "dummy-dir")
                                put("sha256", "dummy")
                            })
                        })
                    })
                })
            }
            File(bundleDir, "02_EVIDENCE_INDEX.json").writeText(index.toString())
            val projectDir = File(bundleDir, "project")
            projectDir.mkdirs()
            File(projectDir, "git_status.txt").writeText("git_status = NOT_A_GIT_REPOSITORY")
            
            val manifest = JSONObject().apply {
                put("bundle_id", "test-bundle")
                put("schema_version", 2)
                put("content_mode", "SOURCE_BASELINE")
                put("project", JSONObject().apply { put("is_git", false) })
            }
            File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(manifest.toString())
            
            listOf("00_READ_ME_FIRST.md", "03_CURRENT_STATUS.md", "04_MISSING_OR_UNVERIFIED.md", "05_ROLLBACK.md").forEach { name ->
                File(bundleDir, name).writeText("Line 1\nLine 2\nLine 3\n")
            }

            val report = verifier.verifyBundle(bundleDir)
            assertFalse(report.success, "Verification should fail for directory evidence")
            assertTrue(report.findings.any { it.id == "DIRECTORY_EVIDENCE" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testMissingHashRejectionForVerifiedClaim() {
        val root = File("temp-hash-test-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val verifier = HandoffVerifier(root)
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            val file = File(bundleDir, "test.txt")
            file.writeText("content")
            
            val index = JSONObject().apply {
                put("bundle_id", "test-bundle")
                put("claims", JSONArray().apply {
                    put(JSONObject().apply {
                        put("claim_id", "TEST-003")
                        put("claim", "Test claim")
                        put("status", "VERIFIED")
                        put("evidence", JSONArray().apply {
                            put(JSONObject().apply {
                                put("path", "test.txt")
                                put("sha256", "missing")
                            })
                        })
                    })
                })
            }
            File(bundleDir, "02_EVIDENCE_INDEX.json").writeText(index.toString())
            val projectDir = File(bundleDir, "project")
            projectDir.mkdirs()
            File(projectDir, "git_status.txt").writeText("git_status = NOT_A_GIT_REPOSITORY")
            
            val manifest = JSONObject().apply {
                put("bundle_id", "test-bundle")
                put("schema_version", 2)
                put("content_mode", "SOURCE_BASELINE")
                put("project", JSONObject().apply { put("is_git", false) })
            }
            File(bundleDir, "01_HANDOFF_MANIFEST.json").writeText(manifest.toString())
            
            listOf("00_READ_ME_FIRST.md", "03_CURRENT_STATUS.md", "04_MISSING_OR_UNVERIFIED.md", "05_ROLLBACK.md").forEach { name ->
                File(bundleDir, name).writeText("Line 1\nLine 2\nLine 3\n")
            }

            val report = verifier.verifyBundle(bundleDir)
            assertFalse(report.success, "Verification should fail for 'missing' hash in verified claim")
            assertTrue(report.findings.any { it.id == "MISSING_HASH_FOR_VERIFIED_CLAIM" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testEmptyRuntimePayloadOmission() {
        val root = File("temp-runtime-test-${UUID.randomUUID()}")
        root.mkdirs()
        try {
            val inputDir = File(root, "overseer-input/runtime")
            inputDir.mkdirs()
            
            // Create a dummy ZIP with "empty" content
            val zipFile = File(inputDir, "packet.zip")
            val zipOut = java.util.zip.ZipOutputStream(zipFile.outputStream())
            zipOut.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zipOut.write(JSONObject().apply {
                put("bundle_id", "rt-123")
                put("hashes", JSONObject())
            }.toString().toByteArray())
            zipOut.closeEntry()
            
            zipOut.putNextEntry(java.util.zip.ZipEntry("packet_verification_report.json"))
            zipOut.write(JSONObject().apply {
                put("status", "EMPTY_RUNTIME_PACKET")
            }.toString().toByteArray())
            zipOut.closeEntry()
            zipOut.close()
            
            val exporter = RuntimePacketExporter(root)
            val targetDir = File(root, "bundle/runtime")
            exporter.export(targetDir)
            
            assertFalse(File(targetDir, "runtime-events.jsonl").exists(), "Empty events should be omitted")
            assertFalse(File(targetDir, "mission-summaries.json").exists(), "Empty summaries should be omitted")
            assertTrue(File(targetDir, "status.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
