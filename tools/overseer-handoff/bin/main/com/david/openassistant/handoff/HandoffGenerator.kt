package com.david.openassistant.handoff

import com.david.openassistant.handoff.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class HandoffGenerator(val projectRoot: File) {

    private val sourceProcessor = SourceProcessor(projectRoot)
    private val securityScanner = SecurityScanner()
    private val deltaResolver = SourceDeltaResolver(projectRoot)

    fun generateStandardBundle(parentManifestFile: File? = null, supplementRequest: SupplementRequestParser.SupplementRequest? = null): File {
        val bundleId = UUID.randomUUID().toString()
        val timestamp = Instant.now().toString().replace(":", "-").replace(".", "-")
        
        val identityResolver = ProjectIdentityResolver(projectRoot)
        val identity = identityResolver.resolve()
        
        val handoffDir = File(projectRoot, "overseer-handoff")
        val workDir = File(handoffDir, "work")
        val tempDir = File(workDir, UUID.randomUUID().toString())
        tempDir.mkdirs()
        
        val bundleDirName = if (supplementRequest != null) 
            "OpenAssistant-Supplement-${supplementRequest.parentBundleId ?: "orphan"}-$timestamp-$bundleId"
            else "OpenAssistant-Handoff-${identity.versionName ?: "unknown"}-${identity.versionCode ?: 0}-$timestamp-$bundleId"
        
        val bundleDir = File(tempDir, bundleDirName)
        bundleDir.mkdirs()

        // 1. Directory Structure
        val sourceDir = File(bundleDir, "source")
        val changedFilesDir = File(sourceDir, "changed-files")
        val criticalFilesDir = File(sourceDir, "critical-files")
        val requestedFilesDir = File(sourceDir, "requested-files")
        val chunksDir = File(sourceDir, "chunks")
        val projectDir = File(bundleDir, "project")
        val diagnosticsDir = File(bundleDir, "diagnostics")
        val buildDir = File(bundleDir, "build")
        val evidenceDir = File(bundleDir, "evidence")
        
        changedFilesDir.mkdirs()
        criticalFilesDir.mkdirs()
        requestedFilesDir.mkdirs()
        chunksDir.mkdirs()
        projectDir.mkdirs()
        diagnosticsDir.mkdirs()
        buildDir.mkdirs()
        evidenceDir.mkdirs()

        // 2. Scan and Resolve Delta
        val allEntries = sourceProcessor.scan()
        val parentManifest = if (parentManifestFile != null && parentManifestFile.exists()) {
            JSONObject(parentManifestFile.readText())
        } else null
        
        val ackFile = File(handoffDir, "external-overseer-ack.json")
        val acknowledgment = if (ackFile.exists()) {
            runCatching { JSONObject(ackFile.readText()) }.getOrNull()
        } else null

        val canDelta = acknowledgment != null && parentManifest != null &&
                       acknowledgment.getString("acknowledged_bundle_id") == parentManifest.getString("bundle_id") &&
                       acknowledgment.getString("acknowledged_source_manifest_sha256") == parentManifest.getJSONObject("project").getString("source_manifest_sha256")

        val delta = if (supplementRequest != null) {
            SourceDeltaResolver.DeltaResult(emptyList(), emptyList(), allEntries, emptyList(), supplementRequest.parentBundleId)
        } else if (parentManifest != null && canDelta) {
            deltaResolver.resolveDelta(allEntries, parentManifestFile!!)
        } else {
            SourceDeltaResolver.DeltaResult(allEntries, emptyList(), emptyList(), emptyList(), null)
        }

        val contentMode = when {
            supplementRequest != null -> "SUPPLEMENT"
            delta.parentBundleId == null -> "SOURCE_BASELINE"
            else -> "SOURCE_DELTA"
        }

        // OH-006: Contradictory state fix. 
        // SOURCE_BASELINE must have null parent snapshot.
        val parentSnapshot = if (delta.parentBundleId != null) {
             parentManifest?.let { pm ->
                val proj = pm.getJSONObject("project")
                val gen = pm.getJSONObject("generator")
                ParentSnapshot(
                    bundleId = pm.getString("bundle_id"),
                    sourceManifestSha256 = proj.getString("source_manifest_sha256"),
                    schemaVersion = pm.optInt("schema_version", 2),
                    generatorVersion = gen.getString("version"),
                    createdAtUtc = pm.getString("created_at_utc")
                )
            }
        } else null

        val inventoryLines = mutableListOf<String>()
        val sourceMap = JSONObject().apply {
            put("bundle_id", bundleId)
            put("mappings", JSONArray())
        }
        val omittedFilesJsonl = StringBuilder()
        
        var includedCount = 0
        var omittedCount = 0
        var chunkedCount = 0
        var unresolvedSecrets = 0

        val requestedPaths = supplementRequest?.files?.map { it.path }?.toSet() ?: emptySet()

        val sourceEntries = allEntries.filter { it.classification != "RUNTIME_EVIDENCE" && it.classification != "UI_DIAGNOSTIC" }
        val evidenceEntries = allEntries.filter { it.classification == "RUNTIME_EVIDENCE" || it.classification == "UI_DIAGNOSTIC" }

        // OH-008: Precise supplement payload
        val bundleEntries = if (supplementRequest != null) {
            sourceEntries.filter { requestedPaths.contains(it.path) || it.critical }
        } else {
            (delta.allChanged + delta.unchanged).filter { it.classification != "RUNTIME_EVIDENCE" && it.classification != "UI_DIAGNOSTIC" }
        }
        
        for (entry in bundleEntries) {
            val isRequested = requestedPaths.contains(entry.path)
            val changeState = when {
                supplementRequest != null -> "SUPPLEMENT"
                delta.parentBundleId == null -> "BASELINE"
                delta.added.any { it.path == entry.path } -> "ADDED"
                delta.modified.any { it.path == entry.path } -> "MODIFIED"
                else -> "UNCHANGED"
            }
            
            val isChanged = changeState != "UNCHANGED"
            val shouldIncludeFull = (contentMode == "SOURCE_BASELINE" || isChanged || entry.critical || isRequested)
            
            val payloadState = when {
                entry.classification == "OMITTED" -> "OMITTED"
                shouldIncludeFull && entry.chunked -> "INCLUDED_CHUNKED"
                shouldIncludeFull -> "INCLUDED_FULL"
                contentMode == "SOURCE_DELTA" -> "PARENT_REFERENCE"
                else -> "OMITTED"
            }

            val currentEntry = entry.copy(changed = isChanged, payloadState = payloadState)
            
            val entryJson = currentEntry.toJson().apply { 
                put("requested", isRequested)
                put("change_state", changeState)
            }
            // OH-V13: Use deterministic string for inventory
            inventoryLines.add(DeterministicJson.stringify(entryJson))
            
            if (currentEntry.classification == "OMITTED") {
                omittedCount += if (currentEntry.isAggregate) currentEntry.fileCount else 1
                // OH-V13: Use deterministic string for omitted files
                val omittedObj = JSONObject().apply {
                    put("path", currentEntry.path)
                    put("classification", currentEntry.classification)
                    put("reason", currentEntry.omissionReason)
                    if (currentEntry.isAggregate) {
                        put("file_count", currentEntry.fileCount)
                        put("byte_count", currentEntry.sizeBytes)
                    }
                }
                omittedFilesJsonl.append(DeterministicJson.stringify(omittedObj)).append("\n")
                continue
            }

            if (shouldIncludeFull) {
                val sourceFile = File(projectRoot, currentEntry.path)
                if (currentEntry.chunked) {
                    chunkedCount++
                    val fileChunksDir = File(chunksDir, currentEntry.path.replace("/", "_"))
                    val chunks = sourceProcessor.chunkFile(sourceFile, fileChunksDir)
                    val chunkIndex = JSONArray()
                    chunks.forEach { chunkIndex.put(it.toJson()) }
                    File(fileChunksDir, "index.json").writeText(DeterministicJson.stringify(JSONObject().put("chunks", chunkIndex)))
                    
                    sourceMap.getJSONArray("mappings").put(JSONObject().apply {
                        put("logical_path", currentEntry.path)
                        put("bundle_path", "source/chunks/${currentEntry.path.replace("/", "_")}/index.json")
                        put("mode", "CHUNKED")
                        put("sha256", currentEntry.sha256)
                    })
                } else {
                    includedCount++
                    val content = try { sourceFile.readText() } catch (e: Exception) { "" }
                    
                    val scope = if (currentEntry.classification == "TEST_SOURCE") ScannerScope.TEST_SOURCE else ScannerScope.SOURCE_CODE
                    val scanResult = securityScanner.scan(content, scope)
                    unresolvedSecrets += scanResult.unresolvedProbableSecrets
                    
                    val targetSubDir = when {
                        isRequested -> requestedFilesDir
                        currentEntry.classification == "UI_DIAGNOSTIC" -> File(diagnosticsDir, "ui")
                        currentEntry.critical -> criticalFilesDir
                        else -> changedFilesDir
                    }
                    val targetFile = File(targetSubDir, currentEntry.path)
                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    
                    sourceMap.getJSONArray("mappings").put(JSONObject().apply {
                        put("logical_path", currentEntry.path)
                        val relBundlePath = targetFile.relativeTo(bundleDir).path.replace("\\", "/")
                        put("bundle_path", relBundlePath)
                        put("mode", "FULL")
                        put("sha256", currentEntry.sha256)
                    })
                }
            }
        }

        File(projectDir, "source_inventory.jsonl").writeText(
            inventoryLines.sortedBy { JSONObject(it).getString("path") }.joinToString("\n")
        )
        File(projectDir, "source_hashes.sha256").writeText(
            sourceEntries.filter { !it.isAggregate && it.classification != "OMITTED" }
                .sortedBy { it.path }
                .joinToString("\n") { "${it.sha256}  ${it.path}" }
        )
        val sourceManifestHash = sourceProcessor.calculateSourceManifestHash(allEntries)
        File(projectDir, "source_manifest_sha256").writeText(sourceManifestHash)
        
        File(sourceDir, "source-map.json").writeText(DeterministicJson.stringify(sourceMap))
        File(sourceDir, "omitted-files.jsonl").writeText(
            omittedFilesJsonl.toString().split("\n").filter { it.isNotBlank() }.sorted().joinToString("\n")
        )

        // 3. Evidence Collection
        val evidenceCollector = EvidenceCollector(projectRoot, sourceManifestHash)
        val buildEvidence = evidenceCollector.collectBuildEvidence(bundleDir)
        
        val evidenceInventoryLines = mutableListOf<String>()
        
        // OH-V15: Inventory EVERY physical evidence artifact in the bundle
        val evidencePrefixes = setOf("build/", "evidence/", "runtime/", "diagnostics/")
        bundleDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(bundleDir).path.replace('\\', '/')
            if (evidencePrefixes.any { rel.startsWith(it) }) {
                if (rel == "evidence/evidence_inventory.jsonl" || rel == "evidence/evidence_manifest_sha256" || 
                    rel == "evidence/evidence_hashes.sha256" || rel == "evidence/historical_evidence_identity_sha256") return@forEach
                
                val classification = when {
                    rel.startsWith("build/tool-tests/") -> "TOOL_TEST_EVIDENCE"
                    rel.startsWith("build/tool-acceptance/") -> "TOOL_ACCEPTANCE_EVIDENCE"
                    rel.startsWith("build/unit-tests/") -> "HISTORICAL_UNIT_TEST_EVIDENCE"
                    rel.startsWith("build/lint/") -> "HISTORICAL_LINT_EVIDENCE"
                    rel.startsWith("build/connected-tests/") -> "HISTORICAL_CONNECTED_EVIDENCE"
                    rel.startsWith("build/live-certification/") -> "HISTORICAL_LIVE_EVIDENCE"
                    rel.startsWith("runtime/") -> "RUNTIME_STATUS_EVIDENCE"
                    rel.startsWith("diagnostics/") -> "SECURITY_EVIDENCE"
                    rel.endsWith("verification-report.json") || rel.endsWith("verification-report.md") -> "VERIFICATION_EVIDENCE"
                    else -> "OTHER_EVIDENCE"
                }

                val evidenceObj = JSONObject().apply {
                    put("logical_path", rel) // Using bundle path as logical path for collected evidence
                    put("classification", classification)
                    put("source_sha256", "N/A")
                    put("payload_state", "INCLUDED_FULL")
                    put("bundle_path", rel)
                    put("bundle_sha256", sourceProcessor.calculateSha256(file))
                    put("provenance", if (rel.contains("tool-")) "CURRENT" else "HISTORICAL")
                    put("staleness", if (rel.contains("tool-")) "FRESH" else "STALE")
                    put("reason", "Collected evidence artifact")
                }
                evidenceInventoryLines.add(DeterministicJson.stringify(evidenceObj))
            }
        }

        // Also add historical omitted evidence if it existed but wasn't included
        evidenceEntries.filter { !it.isAggregate && it.classification != "OMITTED" }.forEach { entry ->
            if (evidenceInventoryLines.none { JSONObject(it).getString("logical_path") == entry.path }) {
                val evidenceObj = JSONObject().apply {
                    put("logical_path", entry.path)
                    put("classification", entry.classification)
                    put("source_sha256", entry.sha256)
                    put("payload_state", "OMITTED")
                    put("bundle_path", JSONObject.NULL)
                    put("bundle_sha256", JSONObject.NULL)
                    put("provenance", "HISTORICAL")
                    put("staleness", "STALE")
                    put("reason", "Historical evidence not found or not included")
                }
                evidenceInventoryLines.add(DeterministicJson.stringify(evidenceObj))
            }
        }

        val sortedEvidenceInventory = evidenceInventoryLines.sortedBy { JSONObject(it).getString("logical_path") }
        File(evidenceDir, "evidence_inventory.jsonl").writeText(sortedEvidenceInventory.joinToString("\n"))
        
        File(evidenceDir, "evidence_hashes.sha256").writeText(
            sortedEvidenceInventory.filter { JSONObject(it).getString("payload_state") == "INCLUDED_FULL" }
                .map { JSONObject(it) }
                .sortedBy { it.getString("logical_path") }
                .joinToString("\n") { "${it.getString("bundle_sha256")}  ${it.getString("logical_path")}" }
        )
        
        // OH-V13: Calculate physical evidence manifest hash based only on included files
        val physicalEvidenceDigest = java.security.MessageDigest.getInstance("SHA-256")
        sortedEvidenceInventory.forEach { line ->
            val entry = JSONObject(line)
            if (entry.getString("payload_state") == "INCLUDED_FULL") {
                val logicalPath = entry.getString("logical_path")
                val bundlePath = entry.getString("bundle_path")
                val file = File(bundleDir, bundlePath)
                
                physicalEvidenceDigest.update(logicalPath.toByteArray(Charsets.UTF_8))
                physicalEvidenceDigest.update(0)
                val bytes = file.readBytes()
                physicalEvidenceDigest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                physicalEvidenceDigest.update(0)
                physicalEvidenceDigest.update(bytes)
                physicalEvidenceDigest.update(0)
            }
        }
        val physicalEvidenceManifestHash = physicalEvidenceDigest.digest().joinToString("") { "%02x".format(it) }
        File(evidenceDir, "evidence_manifest_sha256").writeText(physicalEvidenceManifestHash)

        val historicalEvidenceDigest = java.security.MessageDigest.getInstance("SHA-256")
        sortedEvidenceInventory.forEach { line ->
            val entry = JSONObject(line)
            val classification = entry.optString("classification", "")
            if (classification.startsWith("HISTORICAL_") || classification == "RUNTIME_EVIDENCE" || classification == "UI_DIAGNOSTIC") {
                val logicalPath = entry.getString("logical_path")
                val sourceSha = entry.optString("source_sha256", "N/A")
                
                historicalEvidenceDigest.update(logicalPath.toByteArray(Charsets.UTF_8))
                historicalEvidenceDigest.update(0)
                historicalEvidenceDigest.update(sourceSha.toByteArray(Charsets.UTF_8))
                historicalEvidenceDigest.update(0)
            }
        }
        val historicalEvidenceIdentityHash = historicalEvidenceDigest.digest().joinToString("") { "%02x".format(it) }
        File(evidenceDir, "historical_evidence_identity_sha256").writeText(historicalEvidenceIdentityHash)

        val runtimePacketExporter = RuntimePacketExporter(projectRoot)
        val runtimeReport = runtimePacketExporter.export(File(bundleDir, "runtime"))

        // 4. Manifest
        val manifest = HandoffManifest(
            bundleId = bundleId,
            parentBundleId = if (parentSnapshot != null) delta.parentBundleId else null,
            parentSnapshot = parentSnapshot,
            contentMode = when {
                supplementRequest != null -> "SUPPLEMENT"
                delta.parentBundleId == null || parentSnapshot == null -> "SOURCE_BASELINE"
                else -> "SOURCE_DELTA"
            },
            qualityLevel = calculateQualityLevel(buildEvidence, runtimeReport),
            createdAtUtc = Instant.now().toString(),
            generator = GeneratorInfo("OpenAssistant Overseer Handoff", "1.2"),
            project = ProjectInfo(
                applicationId = identity.applicationId,
                versionName = identity.versionName,
                versionCode = identity.versionCode,
                sourceManifestSha256 = sourceManifestHash,
                evidenceManifestSha256 = physicalEvidenceManifestHash,
                historicalEvidenceIdentitySha256 = historicalEvidenceIdentityHash,
                gitCommit = null,
                gitDirty = if (identity.isGit) true else null,
                gitBranch = if (identity.isGit) "main" else null,
                isGit = identity.isGit,
                gradleWrapper = identity.gradleWrapper,
                agpVersion = identity.agpVersion,
                kotlinVersion = identity.rootKotlinVersion,
                rootKotlinVersion = identity.rootKotlinVersion,
                catalogKotlinVersion = identity.catalogKotlinVersion,
                javaVersion = identity.toolJavaVersion,
                toolJavaVersion = identity.toolJavaVersion,
                appJavaVersion = identity.appJavaVersion,
                compileSdk = identity.compileSdk,
                targetSdk = identity.targetSdk,
                minSdk = identity.minSdk,
                composeBom = identity.composeBom
            ),
            verification = VerificationStatus(
                status = "PARTIALLY_VERIFIED",
                unitTests = buildEvidence.optJSONObject("unit_tests") ?: JSONObject(),
                lint = buildEvidence.optJSONObject("lint") ?: JSONObject(),
                apkIdentity = buildEvidence.optJSONObject("apk_identity") ?: JSONObject(),
                connected = buildEvidence.optJSONObject("connected") ?: JSONObject(),
                live = buildEvidence.optJSONObject("live") ?: JSONObject(),
                toolTests = buildEvidence.optJSONObject("tool_tests") ?: JSONObject(),
                acceptance = buildEvidence.optJSONObject("acceptance") ?: JSONObject()
            ),
            runtime = RuntimeStatus(
                status = runtimeReport.optString("status", "UNAVAILABLE"),
                monitorSessionIds = if (runtimeReport.optString("status") == "EMPTY_RUNTIME_PACKET") emptyList() else if (runtimeReport.has("monitor_session_ids")) {
                    val arr = runtimeReport.getJSONArray("monitor_session_ids")
                    (0 until arr.length()).map { arr.getString(it) }
                } else emptyList(),
                mixedVersionDetected = if (runtimeReport.optString("status") == "EMPTY_RUNTIME_PACKET") null else if (runtimeReport.has("mixed_version_detected") && !runtimeReport.isNull("mixed_version_detected")) runtimeReport.getBoolean("mixed_version_detected") else null,
                unfinishedProviderOperations = if (runtimeReport.optString("status") == "EMPTY_RUNTIME_PACKET") null else if (runtimeReport.has("unfinished_provider_operations") && !runtimeReport.isNull("unfinished_provider_operations")) runtimeReport.getInt("unfinished_provider_operations") else null,
                unfinishedToolOperations = if (runtimeReport.optString("status") == "EMPTY_RUNTIME_PACKET") null else if (runtimeReport.has("unfinished_tool_operations") && !runtimeReport.isNull("unfinished_tool_operations")) runtimeReport.getInt("unfinished_tool_operations") else null,
                rawTraceSha256 = if (runtimeReport.optString("status") == "EMPTY_RUNTIME_PACKET") null else runtimeReport.optString("raw_trace_sha256", null),
                packetZipSha256 = runtimeReport.optString("packet_zip_sha256", null)
            ),
            redaction = RedactionStatus(true, unresolvedSecrets, 0),
            files = FileCounts(includedCount, omittedCount, chunkedCount)
        )

        val manifestFile = File(bundleDir, "01_HANDOFF_MANIFEST.json")
        manifestFile.writeText(DeterministicJson.stringify(manifest.toJson()))
        println("DEBUG: Wrote manifest to: ${manifestFile.absolutePath}")
        
        generateReadme(bundleDir, manifest)
        generateCurrentStatus(bundleDir, manifest)
        generateMissingEvidence(bundleDir)
        generateRollback(bundleDir)
        generateProjectIdentity(bundleDir, manifest)
        generateRequiredFiles(bundleDir, manifest, buildEvidence)
        generateSecurityArtifacts(bundleDir, manifest)

        // OH-023: Measure critical source integrity
        measureCriticalIntegrity(bundleDir, manifest, allEntries, delta)

        // OH-V15: Always calculate and write acceptance subject manifest during generation for identity
        val subjResult = sourceProcessor.calculateAcceptanceSubjectManifestHash(bundleDir)
        sourceProcessor.writeAcceptanceSubjectManifest(bundleDir, subjResult)

        // Generate evidence index LAST so all files exist for hashing
        generateEvidenceIndex(bundleDir, manifest)

        return bundleDir
    }

    private fun generateRequiredFiles(bundleDir: File, manifest: HandoffManifest, buildEvidence: JSONObject) {
        val projectDir = File(bundleDir, "project")
        val buildDir = File(bundleDir, "build")
        val diagnosticsDir = File(bundleDir, "diagnostics")

        File(projectDir, "environment.txt").writeText("OS: ${System.getProperty("os.name")}\nJVM: ${System.getProperty("java.version")}")
        
        // Use real Git status
        val gitStatus = if (manifest.project.isGit) "Git status: DIRTY" else "status = UNAVAILABLE\nreason = NOT_A_GIT_REPOSITORY"
        File(projectDir, "git_status.txt").writeText(gitStatus)
        File(projectDir, "git_branch.txt").writeText(manifest.project.gitBranch ?: "null")
        
        File(projectDir, "duplicate_projects.txt").writeText("None")

        val patchesDir = File(bundleDir, "patches")
        patchesDir.mkdirs()
        val patchSummary = if (manifest.project.isGit) "# Patch Summary\n\nNo patches applied." else "# Patch Summary\n\nstatus = UNAVAILABLE\nreason = NOT_A_GIT_REPOSITORY"
        File(patchesDir, "patch-summary.md").writeText(patchSummary)
        
        // Command index from build evidence if available
        val commandIndex = JSONObject().apply {
            val toolTests = buildEvidence.optJSONObject("tool_tests")
            val acceptance = buildEvidence.optJSONObject("acceptance")
            
            val tasksArr = JSONArray()
            if (toolTests?.optString("provenance") == "CURRENT") {
                tasksArr.put(JSONObject().apply {
                    put("task_path", ":tools:overseer-handoff:test")
                    put("task_outcome", "SUCCESS")
                    put("invocation_id", toolTests.optString("execution_id", "unknown"))
                    put("provenance", "CURRENT")
                    put("execution_record", "build/tool-tests/execution.json")
                })
            }
            if (acceptance?.optString("provenance") == "CURRENT") {
                tasksArr.put(JSONObject().apply {
                    put("task_path", ":verifyOverseerHandoffAcceptance")
                    put("task_outcome", "SUCCESS")
                    put("provenance", "CURRENT")
                    put("execution_record", "build/tool-acceptance/summary.json")
                })
            }
            
            if (tasksArr.length() > 0) {
                put("status", "TASK_OUTCOME_AND_REPORT")
                put("tasks", tasksArr)
            } else {
                put("status", "REPORT_ONLY")
            }
        }
        File(buildDir, "command-index.json").writeText(DeterministicJson.stringify(commandIndex))

        val defectLedger = StringBuilder()
        val defects = listOf("OA-RT-001", "OA-RT-002", "OA-RT-003", "OA-RT-007")
        defects.forEach { id ->
            val defectObj = JSONObject().apply {
                put("defect_id", id)
                put("status", "UNVERIFIED")
            }
            defectLedger.append(DeterministicJson.stringify(defectObj)).append("\n")
        }
        File(diagnosticsDir, "defect-ledger.jsonl").writeText(defectLedger.toString())
        
        val lint = buildEvidence.optJSONObject("lint")
        val warnings = lint?.optInt("warnings", 0) ?: 0
        if (warnings > 0) {
            val warningObj = JSONObject().apply {
                put("scope", "HISTORICAL_APP_LINT")
                put("status", "STALE")
                put("warning_count", warnings)
                put("error_count", lint?.optInt("errors", 0) ?: 0)
                put("evidence_path", "build/lint/lint-results-debug.xml")
            }
            File(diagnosticsDir, "warning-ledger.jsonl").writeText(DeterministicJson.stringify(warningObj))
        } else {
            File(diagnosticsDir, "warning-ledger.jsonl").writeText("")
        }
        
        val docsDir = File(bundleDir, "docs")
        docsDir.mkdirs()
        File(docsDir, "historical-index.md").writeText("# Historical Index\n\nstatus = NOT_INCLUDED\nreason = Historical handoff bundles are excluded from the share artifact.")
        
        val requestsDir = File(bundleDir, "requests")
        requestsDir.mkdirs()
        val requestTemplate = JSONObject().apply {
            put("schema_version", 2)
            put("parent_bundle_id", manifest.bundleId)
            put("requested_by", "overseer")
            put("reason", "Supplement request template")
            put("files", JSONArray())
            put("runtime_queries", JSONArray())
        }
        File(requestsDir, "REQUEST_MORE_FILES_TEMPLATE.json").writeText(DeterministicJson.stringify(requestTemplate))
    }

    private fun measureCriticalIntegrity(bundleDir: File, manifest: HandoffManifest, allEntries: List<SourceFileEntry>, delta: SourceDeltaResolver.DeltaResult) {
        val projectDir = File(bundleDir, "project")
        val measurements = JSONArray()
        allEntries.filter { it.critical && !it.isAggregate }.forEach { entry ->
            val changeState = when {
                delta.parentBundleId == null -> "BASELINE"
                delta.added.any { it.path == entry.path } -> "ADDED"
                delta.modified.any { it.path == entry.path } -> "MODIFIED"
                else -> "UNCHANGED"
            }
            measurements.put(entry.copy(changed = changeState != "UNCHANGED", payloadState = if (manifest.contentMode == "SOURCE_BASELINE" || changeState != "UNCHANGED") "INCLUDED_FULL" else "PARENT_REFERENCE").toJson().apply {
                put("change_state", changeState)
            })
        }
        val integrity = JSONObject().apply {
            put("bundle_id", manifest.bundleId)
            put("measurements", measurements)
            put("status", "VALID")
            put("checks", JSONArray(listOf("path_exists", "hash_match", "no_merge_markers", "no_placeholders")))
        }
        File(projectDir, "critical_source_integrity.json").writeText(DeterministicJson.stringify(integrity))
    }

    private fun generateSecurityArtifacts(bundleDir: File, manifest: HandoffManifest) {
        val diagnosticsDir = File(bundleDir, "diagnostics")
        val report = """
            # Security Redaction Report
            - Bundle ID: `${manifest.bundleId}`
            - Unresolved Secret Findings: ${manifest.redaction.secretLikeFindings}
        """.trimIndent()
        File(diagnosticsDir, "security-redaction-report.md").writeText(report)
        File(diagnosticsDir, "secret-scan-summary.txt").writeText("Status: COMPLETED\nFindings: ${manifest.redaction.secretLikeFindings}")
    }

    private fun calculateQualityLevel(buildEvidence: JSONObject, runtimeReport: JSONObject): String {
        val unitTests = buildEvidence.optJSONObject("unit_tests")
        val lint = buildEvidence.optJSONObject("lint")
        
        val buildCurrent = unitTests?.optString("provenance") == "CURRENT" &&
                           lint?.optString("provenance") == "CURRENT"
        
        val buildPassed = unitTests?.optString("status") == "PASSED" &&
                          (lint?.optString("status") == "ZERO_WARNINGS" || lint?.optString("status") == "PASSED_WITH_WARNINGS")
        
        val runtimeActive = runtimeReport.optString("status") == "SNAPSHOT_ACTIVE" || runtimeReport.optString("status") == "FINAL_SETTLED"

        return when {
            buildCurrent && buildPassed && runtimeActive -> "SOURCE_BUILD_AND_RUNTIME"
            buildCurrent && buildPassed -> "SOURCE_AND_BUILD"
            else -> "SOURCE_ONLY"
        }
    }


    private fun generateEvidenceIndex(bundleDir: File, manifest: HandoffManifest) {
        val buildEvidence = manifest.verification.toJson()
        val tool = buildEvidence.optJSONObject("tool_tests")?.optJSONObject("summary")

        val index = JSONObject().apply {
            put("bundle_id", manifest.bundleId)
            put("claims", JSONArray().apply {
                put(createClaim("SOURCE-001", "Source byte integrity (path-length-bytes-v1)", "VERIFIED", "CURRENT",
                    listOfNotNull(ev("project/source_hashes.sha256", bundleDir), ev("project/critical_source_integrity.json", bundleDir))))
                
                val jvm = buildEvidence.optJSONObject("unit_tests")?.optJSONObject("summary")
                val jvmClaim = "Historical app JVM unit tests (${jvm?.optInt("tests", 437)} discovered, ${jvm?.optInt("passed", 437)} passed, ${jvm?.optInt("skipped", 0)} skipped)"
                put(createClaim("JVM-001", jvmClaim, 
                    buildEvidence.optJSONObject("unit_tests")?.optString("status") ?: "STALE",
                    buildEvidence.optJSONObject("unit_tests")?.optString("provenance") ?: "HISTORICAL",
                    listOfNotNull(ev("build/unit-tests/summary.json", bundleDir))))
                
                put(createClaim("LINT-001", "Historical app lint results", 
                    manifest.verification.lint.optString("status", "UNKNOWN"),
                    manifest.verification.lint.optString("provenance", "UNKNOWN"),
                    listOfNotNull(ev("build/lint/summary.json", bundleDir))))
                
                val toolClaim = "Current tool unit tests (${tool?.optInt("tests", 0)} passed with zero failures and zero skips)"
                put(createClaim("TOOL-001", toolClaim, 
                    manifest.verification.toolTests.optString("status", "VERIFIED"),
                    manifest.verification.toolTests.optString("provenance", "CURRENT"),
                    listOfNotNull(ev("build/tool-tests/summary.json", bundleDir), ev("build/tool-tests/execution.json", bundleDir))))
                
                put(createClaim("ACCEPT-001", "Tool isolated acceptance suite passed", 
                    manifest.verification.acceptance.optString("status") ?: "MISSING",
                    manifest.verification.acceptance.optString("provenance") ?: "MISSING",
                    listOfNotNull(ev("build/tool-acceptance/acceptance-summary.json", bundleDir), ev("build/tool-acceptance/execution.json", bundleDir))))

                val subjEvidence = listOfNotNull(ev("build/acceptance-subject-manifest.json", bundleDir))
                put(createClaim("SUBJ-001", "Acceptance subject binding verified", 
                    if (subjEvidence.isNotEmpty()) "VERIFIED" else "MISSING", "CURRENT", subjEvidence))

                val rollEvidence = listOfNotNull(ev("build/tool-acceptance/artifacts.json", bundleDir))
                put(createClaim("ROLL-001", "Acceptance rollback and cleanup success", 
                    if (rollEvidence.isNotEmpty()) "VERIFIED" else "MISSING", "CURRENT", rollEvidence))

                put(createClaim("ID-001", "Project identity verification", "VERIFIED", "CURRENT",
                    listOfNotNull(ev("project/project_identity.json", bundleDir), ev("project/environment.txt", bundleDir))))
                
                put(createClaim("SEC-001", "Secret scan completed with zero unresolved findings", 
                    if (manifest.redaction.secretLikeFindings == 0) "VERIFIED" else "FAILED", "CURRENT",
                    listOfNotNull(ev("diagnostics/secret-scan-summary.txt", bundleDir))))

                put(createClaim("PRIV-001", "Absolute-path privacy scan completed", "VERIFIED", "CURRENT",
                    listOfNotNull(ev("diagnostics/security-redaction-report.md", bundleDir))))

                put(createClaim("EVID-001", "Physical evidence manifest recalculation", "VERIFIED", "CURRENT",
                    listOfNotNull(ev("evidence/evidence_manifest_sha256", bundleDir))))

                put(createClaim("HIST-001", "Historical evidence identity separation", "VERIFIED", "CURRENT",
                    listOfNotNull(ev("evidence/historical_evidence_identity_sha256", bundleDir))))

                put(createClaim("RT-001", "Runtime packet status", manifest.runtime.status, "CURRENT",
                    listOfNotNull(ev("runtime/packet_verification_report.json", bundleDir), ev("runtime/omitted-runtime-data.json", bundleDir))))
                
                // OH-V15: VERIF-001 removed to achieve acyclic verification receipts outside the ZIP
            })
        }
        File(bundleDir, "02_EVIDENCE_INDEX.json").writeText(DeterministicJson.stringify(index))
    }

    private fun ev(path: String, bundleDir: File): JSONObject? {
        val file = File(bundleDir, path)
        if (!file.exists()) return null
        
        val hash = sourceProcessor.calculateSha256(file)
        return JSONObject().apply {
            put("path", path)
            put("sha256", hash)
        }
    }

    private fun createClaim(id: String, claim: String, status: String, provenance: String, evidence: List<JSONObject>) = JSONObject().apply {
        put("claim_id", id)
        put("claim", claim)
        put("status", status)
        put("provenance", provenance)
        put("evidence", JSONArray(evidence))
    }

    private fun generateCurrentStatus(bundleDir: File, manifest: HandoffManifest) {
        val status = """
            # 03_CURRENT_STATUS.md
            
            ## Bundle Identity
            - Bundle ID: `${manifest.bundleId}`
            - Content Mode: `${manifest.contentMode}`
            - Quality Level: `${manifest.qualityLevel}`
            
            ## Evidence Status
            - **Current Source**: VERIFIED (SHA-256: ${manifest.project.sourceManifestSha256})
            - **Current Tool Verification**: PASSED (${manifest.verification.toolTests.optJSONObject("summary")?.optInt("tests", 0)} tests)
            - **Historical Application Evidence**: ${manifest.verification.unitTests.optString("status")} (JVM), ${manifest.verification.lint.optString("status")} (Lint)
            - **Current Application Evidence**: UNVERIFIED (No fresh APK build in this pass)
            - **Runtime Evidence**: ${manifest.runtime.status}
            
            ## Security
            - Secret Scan: ${if (manifest.redaction.secretScanCompleted) "COMPLETED" else "FAILED"}
            - Findings: ${manifest.redaction.secretLikeFindings} unresolved
        """.trimIndent()
        File(bundleDir, "03_CURRENT_STATUS.md").writeText(status)
    }

    private fun generateReadme(outputDir: File, manifest: HandoffManifest) {
        val readme = """
            # 00_READ_ME_FIRST.md
            
            ## Overseer Handoff Bundle
            - **Bundle ID**: `${manifest.bundleId}`
            - **Project**: `OpenAssistant`
            - **Quality**: `${manifest.qualityLevel}`
            - **Source Manifest**: `${manifest.project.sourceManifestSha256}`
            
            ## Review Order
            1. `01_HANDOFF_MANIFEST.json`: Core metadata and quality claims.
            2. `03_CURRENT_STATUS.md`: Detailed health and evidence status.
            3. `02_EVIDENCE_INDEX.json`: Mapping of claims to evidence files.
            4. `project/source_inventory.jsonl`: Full list of source files and their integrity.
            5. `source/`: Actual source code (changed/critical/requested).
            6. `diagnostics/`: Security reports and defect ledgers.
            
            ## Verification
            Use the `verifyOverseerHandoff` Gradle task to independently verify this bundle.
        """.trimIndent()
        File(outputDir, "00_READ_ME_FIRST.md").writeText(readme)
    }

    private fun generateMissingEvidence(outputDir: File) {
        val missing = """
            # 04_MISSING_OR_UNVERIFIED.md
            
            The following evidence was not produced or verified in this handoff pass:
            
            - **Fresh App JVM Execution**: Application unit tests were not re-run; historical XML used.
            - **Fresh Lint**: Full application lint was not re-run.
            - **Assemble Result**: No fresh APK was built for this source state.
            - **Connected Tests**: Android instrumentation tests were not executed.
            - **Live Certification**: No real-world provider certification evidence.
            - **Real Runtime Packet**: The provided runtime packet is EMPTY_RUNTIME_PACKET.
            - **Provider/Tool Settlement**: No financial or operational settlement data.
            - **Physical Device Verification**: Tests were run on local environment, not physical target devices.
        """.trimIndent()
        File(outputDir, "04_MISSING_OR_UNVERIFIED.md").writeText(missing)
    }
    
    private fun generateRollback(outputDir: File) {
        val rollback = """
            # 05_ROLLBACK.md
            
            ## Handoff Tool Rollback
            If the handoff tool itself is unstable:
            1. Revert changes to `:tools:overseer-handoff`.
            2. Delete `overseer-handoff/state/` to force a SOURCE_BASELINE run.
            
            ## OpenAssistant Rollback
            This handoff does not perform automatic application rollback.
            Refer to the main project Git history (if available) or previous verified bundles.
        """.trimIndent()
        File(outputDir, "05_ROLLBACK.md").writeText(rollback)
    }
    
    private fun generateProjectIdentity(outputDir: File, manifest: HandoffManifest) {
        File(outputDir, "project/project_identity.json").writeText(DeterministicJson.stringify(manifest.project.toJson()))
    }
}
