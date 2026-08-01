package com.david.openassistant.handoff

import com.david.openassistant.handoff.model.SourceFileEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

class SourceProcessor(val projectRoot: File) {

    private val CHUNK_SIZE_THRESHOLD = 500 * 1024 // 500 KB
    private val CHUNK_LINE_THRESHOLD = 4000

    private val excludedDirNames = setOf(
        "build", ".gradle", ".idea", ".kotlin", ".cxx", "externalNativeBuild", "captures", "node_modules", ".artifacts",
        ".gradle_home", "overseer-handoff", "work", "UPLOAD_THIS", "state", "out", "temp_verify"
    )
    
    private val excludedFiles = setOf(
        "local.properties", "handoff.lock", "gradle-wrapper.jar"
    )

    fun scan(): List<SourceFileEntry> {
        val entries = mutableListOf<SourceFileEntry>()
        val root = projectRoot.canonicalFile
        
        root.listFiles()?.forEach { file ->
            processFileOrDir(file, root, entries)
        }
        return entries
    }

    private fun processFileOrDir(file: File, root: File, entries: MutableList<SourceFileEntry>) {
        val relativePath = file.relativeTo(root).path.replace("\\", "/")
        val segments = relativePath.split("/")
        
        if (file.isDirectory) {
            // OH-V13/V14: Skip any handoff output dir for stability
            if (file.name == "overseer-handoff" && relativePath != "tools/overseer-handoff") return
            
            val isToolModule = relativePath == "tools/overseer-handoff" || relativePath.startsWith("tools/overseer-handoff/")
            val isActuallyGenerated = relativePath.startsWith("overseer-handoff/") ||
                                       (relativePath.contains("/overseer-handoff") && !isToolModule) ||
                                       (file.name.startsWith("extract-verify-"))

            val isCommonExcluded = (segments.any { excludedDirNames.contains(it) && it != "overseer-handoff" }) || 
                                   (isActuallyGenerated && !isToolModule && !relativePath.startsWith("tools/overseer-handoff/src"))

            if (isCommonExcluded) {
                entries.add(createAggregateOmittedEntry(file, relativePath, "EXCLUDED_DIR"))
            } else {
                file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                    processFileOrDir(child, root, entries)
                }
            }
        } else {
            if (shouldExclude(file, relativePath, segments)) {
                entries.add(createOmittedEntry(file, relativePath, "EXCLUDED_FILE"))
            } else if (isProjectOwned(file, relativePath)) {
                entries.add(createIncludedEntry(file, relativePath))
            } else {
                entries.add(createOmittedEntry(file, relativePath, "THIRD_PARTY_OR_UNKNOWN"))
            }
        }
    }

    private fun createAggregateOmittedEntry(dir: File, relativePath: String, reason: String): SourceFileEntry {
        var fileCount = 0
        var totalBytes = 0L
        dir.walkTopDown().forEach { 
            if (it.isFile) {
                fileCount++
                totalBytes += it.length()
            }
        }
        return SourceFileEntry(
            path = relativePath,
            sizeBytes = totalBytes,
            lineCount = 0,
            sha256 = "",
            classification = "OMITTED",
            changed = false,
            critical = false,
            includedFull = false,
            chunked = false,
            omissionReason = reason,
            isAggregate = true,
            fileCount = fileCount
        )
    }

    private fun shouldExclude(file: File, relativePath: String, segments: List<String>): Boolean {
        if (excludedFiles.contains(file.name)) return true
        
        if (segments.contains("overseer-handoff") && !relativePath.startsWith("tools/overseer-handoff")) return true
        if (relativePath.contains("tools/overseer-handoff/overseer-handoff")) return true
        
        if (segments.any { excludedDirNames.contains(it) && it != "overseer-handoff" }) return true
        if (file.name.startsWith("extract-verify-")) return true
        
        // OH-V13: Exclude privacy-risky generated evidence
        if (relativePath.startsWith("evidence/source-freeze/") || relativePath.startsWith("evidence/source-identity/")) return true
        
        // Exclude monitor packets and request files
        if (relativePath.contains("overseer-input/runtime/")) return true
        if (file.name == "overseer-request.json" || file.name == "overseer-requested-files.txt") return true
        
        return false
    }

    fun isProjectOwned(file: File, relativePath: String): Boolean {
        if (relativePath.startsWith("tools/overseer-handoff/src")) return true
        
        if (file.name == "libs.versions.toml" || file.name == "gradle-wrapper.properties" || 
            file.name == "settings.gradle.kts" || file.name == "gradle.properties" ||
            file.name == ".gitignore" || file.name == "gradlew" || file.name == "gradlew.bat") return true

        val ext = file.extension.lowercase()
        return when (ext) {
            "kt", "java", "kts", "gradle", "xml", "pro", "properties", "md", "json", "jsonl", "keep", "ps1", "sh", "cmd", "txt" -> true
            "webp", "png", "jpg", "jpeg", "svg", "ico" -> true // Small assets
            else -> false
        }
    }

    fun classify(file: File, relativePath: String): String {
        val ext = file.extension.lowercase()
        return when {
            file.name == "OPENASSISTANT_RESEARCH_MONITOR_REPORT.md" || 
            relativePath.contains("overseer-input/runtime/") ||
            relativePath.startsWith("evidence/") || 
            relativePath.startsWith("diagnostics/") -> "RUNTIME_EVIDENCE"

            file.name.startsWith("view") && file.extension == "xml" && (relativePath.contains("diagnostics") || !relativePath.contains("src/main/res")) -> "UI_DIAGNOSTIC"
            
            relativePath.contains("src/main/java") || relativePath.contains("src/main/kotlin") -> "PRODUCTION_SOURCE"
            relativePath.contains("src/test") || relativePath.contains("src/androidTest") -> "TEST_SOURCE"
            
            relativePath.endsWith(".gradle.kts") || relativePath.endsWith(".gradle") || 
            relativePath.contains("gradle/") || file.name == "libs.versions.toml" || 
            file.name == "gradle.properties" -> "BUILD_CONFIGURATION"

            file.name == ".gitignore" -> "PROJECT_CONFIGURATION"
            ext == "keep" -> "BUILD_CONFIGURATION"
            ext in setOf("ps1", "sh", "cmd") -> "TOOL_SCRIPT"
            file.name in setOf("gradlew", "gradlew.bat") -> "GRADLE_WRAPPER_SCRIPT"
            
            relativePath.contains("src/main/res") || relativePath.contains("src/main/assets") -> "ANDROID_RESOURCE"
            file.name == "AndroidManifest.xml" -> "MANIFEST"
            file.extension == "md" -> "DOCUMENTATION"
            else -> "OTHER_PROJECT_FILE"
        }
    }

    private fun isCritical(file: File, relativePath: String): Boolean {
        val name = file.name
        return when (name) {
            "AgentGoalWorker.kt", "AgentScheduler.kt", "ProviderRequestLedger.kt",
            "AutonomousToolRuntime.kt", "ResearchMonitor.kt", "ResearchMonitorReport.kt",
            "SearchQueryValidator.kt", "AgentVerifier.kt", "AgentModels.kt", "AgentStore.kt",
            "HandoffGenerator.kt", "HandoffVerifier.kt" -> true
            else -> false
        }
    }

    private fun createIncludedEntry(file: File, relativePath: String): SourceFileEntry {
        val sha256 = calculateSha256(file)
        val lines = try { file.readLines().size } catch (e: Exception) { 0 }
        val size = file.length()
        val chunked = size > CHUNK_SIZE_THRESHOLD || lines > CHUNK_LINE_THRESHOLD

        return SourceFileEntry(
            path = relativePath,
            sizeBytes = size,
            lineCount = lines,
            sha256 = sha256,
            classification = classify(file, relativePath),
            changed = true, 
            critical = isCritical(file, relativePath),
            includedFull = !chunked,
            chunked = chunked
        )
    }

    private fun createOmittedEntry(file: File, relativePath: String, reason: String): SourceFileEntry {
        return SourceFileEntry(
            path = relativePath,
            sizeBytes = file.length(),
            lineCount = 0,
            sha256 = "",
            classification = "OMITTED",
            changed = false,
            critical = false,
            includedFull = false,
            chunked = false,
            omissionReason = reason
        )
    }

    fun calculateSha256(file: File): String {
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

    fun calculateSourceManifestHash(entries: List<SourceFileEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // OH-V12: algorithm_id = path-length-bytes-v1
        // For each file, sorted by normalized project-relative path:
        // UTF-8 relative path, NUL, decimal byte length, NUL, raw file bytes, NUL
        
        entries.filter { 
            it.classification != "OMITTED" && it.classification != "UI_DIAGNOSTIC" && it.classification != "RUNTIME_EVIDENCE"
        }.sortedBy { it.path }.forEach { entry ->
            val file = File(projectRoot, entry.path)
            if (file.exists() && file.isFile) {
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                val bytes = file.readBytes()
                digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(bytes)
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun calculateAcceptanceSubjectManifestHash(bundleDir: File): SubjectResult {
        val includedPaths = mutableListOf<String>()
        val excludedPaths = mutableListOf<String>()
        val fileHashes = mutableMapOf<String, JSONObject>()
        
        // OH-V15: Explicit exact-file allowlist for the acceptance subject
        
        val allowedFiles = setOf(
            "project/project_identity.json", 
            "diagnostics/secret-scan-summary.txt", "diagnostics/security-redaction-report.md",
            "build/tool-tests/summary.json", "build/tool-tests/execution.json",
            "build/command-index.json"
        )

        bundleDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(bundleDir).path.replace('\\', '/')
            
            // Inclusion rules: 
            // 1. Only tool module source (to ensure stability of tool acceptance across projects)
            // 2. Explicitly allowed metadata files in other dirs
            val isIncluded = rel.startsWith("source/changed-files/tools/overseer-handoff/") || 
                             rel.startsWith("source/critical-files/tools/overseer-handoff/") ||
                             rel.startsWith("source/requested-files/tools/overseer-handoff/") ||
                             allowedFiles.contains(rel) || 
                             (rel.startsWith("diagnostics/ui/") && rel.endsWith(".xml"))

            if (!isIncluded) {
                excludedPaths.add(rel)
            } else {
                includedPaths.add(rel)
                
                val bytes = if (file.name.endsWith(".json") || file.name.endsWith(".jsonl")) {
                    val content = file.readText().replace("\r\n", "\n")
                    if (file.name.endsWith(".json")) {
                        val json = runCatching { JSONObject(content) }.getOrNull()
                        if (json != null) {
                            maskVolatileFields(json)
                            DeterministicJson.stringify(json).toByteArray(Charsets.UTF_8)
                        } else content.toByteArray(Charsets.UTF_8)
                    } else {
                        // JSONL: mask each line if it's JSON, then sort lines for determinism
                        content.split("\n").filter { it.isNotBlank() }.map { line ->
                            val json = runCatching { JSONObject(line) }.getOrNull()
                            if (json != null) {
                                maskVolatileFields(json)
                                DeterministicJson.stringify(json)
                            } else line
                        }.sorted().joinToString("\n").toByteArray(Charsets.UTF_8)
                    }
                } else if (file.name.endsWith(".md") || file.name.endsWith(".txt")) {
                    file.readText().replace("\r\n", "\n")
                        .replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "[UUID]")
                        .replace(Regex("[0-9a-f]{64}"), "[SHA256]")
                        .toByteArray(Charsets.UTF_8)
                } else {
                    file.readBytes()
                }
                
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(bytes)
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                fileHashes[rel] = JSONObject().apply {
                    put("path", rel)
                    put("sha256", hash)
                }
            }
        }
            
        val sortedIncluded = includedPaths.sorted()
        val finalDigest = MessageDigest.getInstance("SHA-256")
        
        // Explicitly using path-sha256-v2 logic: path + NUL + hash + NUL
        sortedIncluded.forEach { path ->
            val h = fileHashes[path]!!.getString("sha256")
            finalDigest.update(path.toByteArray(Charsets.UTF_8))
            finalDigest.update(0)
            finalDigest.update(h.toByteArray(Charsets.UTF_8))
            finalDigest.update(0)
        }
        
        val finalHash = finalDigest.digest().joinToString("") { "%02x".format(it) }
        
        return SubjectResult(finalHash, sortedIncluded, excludedPaths.sorted())
    }

    data class SubjectResult(val hash: String, val included: List<String>, val excluded: List<String>)

    fun writeAcceptanceSubjectManifest(bundleDir: File, result: SubjectResult) {
        val manifest = JSONObject().apply {
            put("algorithm_id", "path-sha256-v2")
            put("included_paths", JSONArray(result.included))
            put("excluded_paths", JSONArray(result.excluded))
            put("file_count", result.included.size)
            put("subject_hash", result.hash)
            put("timestamp_utc", Instant.now().toString())
        }
        
        val subjectManifestFile = File(bundleDir, "build/acceptance-subject-manifest.json")
        subjectManifestFile.parentFile.mkdirs()
        subjectManifestFile.writeText(DeterministicJson.stringify(manifest))
    }

    private fun maskVolatileFields(json: JSONObject) {
        val keys = json.keys().asSequence().toList()
        for (key in keys) {
            val value = json.opt(key)
            val lowered = key.lowercase()
            
            // Mask instead of remove for stability
            if (lowered.contains("bundle_id") || lowered.contains("execution_id") || 
                lowered.contains("timestamp") || lowered.contains("invocation_id") || 
                lowered.contains("at_utc") || lowered.contains("at_ms") || 
                lowered.contains("duration") || lowered.contains("sha256") ||
                lowered == "timestamp_ms" || (lowered == "id" && value is String && value.length > 30)) {
                json.put(key, "[VOLATILE]")
            } else if (value is JSONObject) {
                maskVolatileFields(value)
            } else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) maskVolatileFields(item)
                }
            }
        }
    }

    fun calculateEvidenceManifestHash(entries: List<SourceFileEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.filter { 
            it.classification == "RUNTIME_EVIDENCE" || it.classification == "UI_DIAGNOSTIC"
        }.sortedBy { it.path }.forEach { entry ->
            val file = File(projectRoot, entry.path)
            if (file.exists() && file.isFile) {
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                val bytes = file.readBytes()
                digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(bytes)
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun chunkFile(file: File, targetDir: File): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        val originalSha256 = calculateSha256(file)
        targetDir.mkdirs()

        val lines = mutableListOf<ByteArray>()
        file.inputStream().use { input ->
            var currentLine = mutableListOf<Byte>()
            var b = input.read()
            while (b != -1) {
                currentLine.add(b.toByte())
                if (b == '\n'.code) {
                    lines.add(currentLine.toByteArray())
                    currentLine = mutableListOf<Byte>()
                }
                b = input.read()
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toByteArray())
            }
        }

        val chunkSize = 1000
        for (i in lines.indices step chunkSize) {
            val end = minOf(i + chunkSize, lines.size)
            val chunkLines = lines.subList(i, end)
            val partNum = (i / chunkSize) + 1
            val partFile = File(targetDir, "part-%04d.txt".format(partNum))
            partFile.outputStream().use { out ->
                chunkLines.forEach { out.write(it) }
            }
            
            chunks.add(ChunkInfo(
                originalPath = file.relativeTo(projectRoot).path.replace("\\", "/"),
                originalSha256 = originalSha256,
                part = partNum,
                startLine = i + 1,
                endLine = end,
                partSha256 = calculateSha256(partFile)
            ))
        }
        return chunks
    }

    data class ChunkInfo(
        val originalPath: String,
        val originalSha256: String,
        val part: Int,
        val startLine: Int,
        val endLine: Int,
        val partSha256: String
    ) {
        fun toJson() = JSONObject().apply {
            put("original_path", originalPath)
            put("original_sha256", originalSha256)
            put("part", part)
            put("start_line", startLine)
            put("end_line", endLine)
            put("part_sha256", partSha256)
        }
    }
}
