package com.david.openassistant.domain.tools

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val MAX_WORKSPACE_FILE_BYTES = 256_000
private const val MAX_WORKSPACE_READ_BYTES = 160_000
private const val MAX_WORKSPACE_FILES = 250
private const val MAX_WORKSPACE_SCAN_FILES = 5_000
private const val MAX_WORKSPACE_SEARCH_MATCHES = 150
private const val MAX_WORKSPACE_PATH_CHARS = 220

object WorkspaceToolCatalog {
    val definitions: List<SafeToolDefinition> = listOf(
        SafeToolDefinition(
            name = "workspace_write_text",
            displayName = "Workspace writer",
            description = "Write or append UTF-8 text inside the app's private autonomous workspace. Paths are sandboxed; no storage permission, external files, execution, or other apps are available.",
            parameters = listOf(
                ToolParameter("path", "Relative workspace path such as reports/research.md."),
                ToolParameter("content", "UTF-8 text content."),
                ToolParameter("mode", "Optional overwrite or append. Defaults to overwrite.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "workspace_read_text",
            displayName = "Workspace reader",
            description = "Read bounded UTF-8 text from the app's private autonomous workspace.",
            parameters = listOf(
                ToolParameter("path", "Relative workspace path."),
                ToolParameter("start_line", "Optional one-based first line. Defaults to 1.", required = false),
                ToolParameter("max_lines", "Optional maximum lines from 1 to 2000. Defaults to 500.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "workspace_list_files",
            displayName = "Workspace inventory",
            description = "List text files and metadata in the app's private autonomous workspace.",
            parameters = listOf(ToolParameter("prefix", "Optional relative directory or filename prefix.", required = false)),
        ),
        SafeToolDefinition(
            name = "workspace_search_text",
            displayName = "Workspace search",
            description = "Search private workspace text files for a literal query and return bounded line matches.",
            parameters = listOf(
                ToolParameter("query", "Literal text to search for."),
                ToolParameter("prefix", "Optional relative directory or filename prefix.", required = false),
                ToolParameter("case_sensitive", "Optional true or false. Defaults to false.", required = false),
            ),
        ),
        SafeToolDefinition(
            name = "workspace_file_info",
            displayName = "Workspace file information",
            description = "Read metadata for one private workspace file without reading its full contents.",
            parameters = listOf(ToolParameter("path", "Relative workspace path.")),
        ),
        SafeToolDefinition(
            name = "workspace_move_to_trash",
            displayName = "Workspace soft delete",
            description = "Move a private workspace file into a recoverable trash area. It never permanently deletes the file.",
            parameters = listOf(ToolParameter("path", "Relative workspace path.")),
        ),
        SafeToolDefinition(
            name = "workspace_restore_from_trash",
            displayName = "Workspace restore",
            description = "Restore a previously trashed private workspace file to its original or specified relative path.",
            parameters = listOf(
                ToolParameter("trash_name", "Trash item name returned by workspace_move_to_trash."),
                ToolParameter("restore_path", "Optional destination relative path. Defaults to the recorded original path.", required = false),
            ),
        ),
    )

    private val names = definitions.mapTo(mutableSetOf()) { it.name }
    fun handles(name: String): Boolean = name in names
}

class WorkspaceToolRuntime(context: Context) {
    private val root = File(context.filesDir, "autonomy/workspace").apply { mkdirs() }.canonicalFile
    private val trash = File(context.filesDir, "autonomy/workspace_trash").apply { mkdirs() }.canonicalFile

    fun execute(call: OpenRouterToolCall): ToolExecutionResult {
        val args = parseArguments(call.argumentsJson)
        return when (call.name) {
            "workspace_write_text" -> writeText(args)
            "workspace_read_text" -> readText(args)
            "workspace_list_files" -> listFiles(args)
            "workspace_search_text" -> searchText(args)
            "workspace_file_info" -> fileInfo(args)
            "workspace_move_to_trash" -> moveToTrash(args)
            "workspace_restore_from_trash" -> restoreFromTrash(args)
            else -> throw ToolValidationException("Unknown workspace tool: ${call.name}")
        }
    }

    fun fileCount(): Int = allFiles(root).size

    private fun writeText(args: JSONObject): ToolExecutionResult {
        val path = requiredString(args, "path")
        val target = resolveWorkspacePath(path, allowMissing = true)
        validateTextFileName(target)
        val content = requiredStringAllowEmpty(args, "content")
        val mode = optionalString(args, "mode")?.lowercase(Locale.US) ?: "overwrite"
        if (mode !in setOf("overwrite", "append")) {
            throw ToolValidationException("workspace_write_text mode must be overwrite or append.")
        }
        val existingBytes = if (target.exists()) target.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        val projected = if (mode == "append") existingBytes + contentBytes.size else contentBytes.size
        if (projected > MAX_WORKSPACE_FILE_BYTES) {
            throw ToolValidationException("Workspace text files are limited to $MAX_WORKSPACE_FILE_BYTES bytes.")
        }
        if (!target.exists() && allFiles(root).size >= MAX_WORKSPACE_FILES) {
            throw ToolValidationException("The private workspace is limited to $MAX_WORKSPACE_FILES files.")
        }
        target.parentFile?.mkdirs()
        val finalBytes = if (mode == "append" && target.exists()) {
            target.readBytes() + contentBytes
        } else {
            contentBytes
        }
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(finalBytes)
            output.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("path", relativePath(target))
                .put("mode", mode)
                .put("bytes", target.length())
                .put("workspace_file_count", fileCount())
                .toString(),
            displaySummary = "${if (mode == "append") "Appended to" else "Wrote"} ${relativePath(target)} (${target.length()} bytes).",
        )
    }

    private fun readText(args: JSONObject): ToolExecutionResult {
        val target = resolveWorkspacePath(requiredString(args, "path"))
        requireRegularTextFile(target)
        if (target.length() > MAX_WORKSPACE_READ_BYTES) {
            throw ToolValidationException("workspace_read_text is limited to $MAX_WORKSPACE_READ_BYTES bytes per file.")
        }
        val startLine = optionalString(args, "start_line")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val maxLines = optionalString(args, "max_lines")?.toIntOrNull()?.coerceIn(1, 2_000) ?: 500
        val allLines = target.readLines(StandardCharsets.UTF_8)
        val selected = allLines.drop(startLine - 1).take(maxLines)
        return ToolExecutionResult(
            outputJson = JSONObject()
                .put("path", relativePath(target))
                .put("start_line", startLine)
                .put("returned_lines", selected.size)
                .put("total_lines", allLines.size)
                .put("truncated", startLine - 1 + selected.size < allLines.size)
                .put("content", selected.joinToString("\n"))
                .toString(),
            displaySummary = "Read ${selected.size} of ${allLines.size} line(s) from ${relativePath(target)}.",
        )
    }

    private fun listFiles(args: JSONObject): ToolExecutionResult {
        val prefix = optionalString(args, "prefix").orEmpty().replace('\\', '/').trim('/')
        val scannedFiles = allFiles(root)
        val matchingFiles = scannedFiles
            .take(MAX_WORKSPACE_SCAN_FILES)
            .filter { prefix.isBlank() || relativePath(it).startsWith(prefix, ignoreCase = true) }
            .sortedBy(::relativePath)
        val entries = matchingFiles
            .take(MAX_WORKSPACE_FILES)
        val array = JSONArray().apply {
            entries.forEach { file ->
                put(
                    JSONObject()
                        .put("path", relativePath(file))
                        .put("bytes", file.length())
                        .put("modified_at", file.lastModified()),
                )
            }
        }
        return ToolExecutionResult(
            JSONObject()
                .put("count", entries.size)
                .put("matching_file_count", matchingFiles.size)
                .put("truncated", matchingFiles.size > entries.size)
                .put("scan_truncated", scannedFiles.size > MAX_WORKSPACE_SCAN_FILES)
                .put("files", array)
                .toString(),
            "Listed ${entries.size} private workspace file(s).",
        )
    }

    private fun searchText(args: JSONObject): ToolExecutionResult {
        val query = requiredString(args, "query").take(2_000)
        val prefix = optionalString(args, "prefix").orEmpty().replace('\\', '/').trim('/')
        val caseSensitive = optionalString(args, "case_sensitive")?.toBooleanStrictOrNull() ?: false
        val needle = if (caseSensitive) query else query.lowercase(Locale.US)
        val matches = JSONArray()
        var count = 0
        val scannedFiles = allFiles(root)
        scannedFiles
            .take(MAX_WORKSPACE_SCAN_FILES)
            .filter { it.length() <= MAX_WORKSPACE_READ_BYTES }
            .filter { prefix.isBlank() || relativePath(it).startsWith(prefix, ignoreCase = true) }
            .sortedBy(::relativePath)
            .forEach fileLoop@ { file ->
                file.useLines(StandardCharsets.UTF_8) { lines ->
                    lines.forEachIndexed { index, line ->
                        if (count >= MAX_WORKSPACE_SEARCH_MATCHES) return@forEachIndexed
                        val haystack = if (caseSensitive) line else line.lowercase(Locale.US)
                        val columnIndex = haystack.indexOf(needle)
                        if (columnIndex >= 0) {
                            matches.put(
                                JSONObject()
                                    .put("path", relativePath(file))
                                    .put("line", index + 1)
                                    .put("column", columnIndex + 1)
                                    .put("excerpt", line.take(500)),
                            )
                            count++
                        }
                    }
                }
                if (count >= MAX_WORKSPACE_SEARCH_MATCHES) return@fileLoop
            }
        return ToolExecutionResult(
            JSONObject()
                .put("query", query)
                .put("count", count)
                .put("truncated", count >= MAX_WORKSPACE_SEARCH_MATCHES)
                .put("scan_truncated", scannedFiles.size > MAX_WORKSPACE_SCAN_FILES)
                .put("matches", matches)
                .toString(),
            "Found $count private workspace match(es) for '$query'.",
        )
    }

    private fun fileInfo(args: JSONObject): ToolExecutionResult {
        val target = resolveWorkspacePath(requiredString(args, "path"))
        requireRegularTextFile(target)
        return ToolExecutionResult(
            JSONObject()
                .put("path", relativePath(target))
                .put("bytes", target.length())
                .put("modified_at", target.lastModified())
                .put("readable", target.canRead())
                .put("writable", target.canWrite())
                .toString(),
            "${relativePath(target)} is ${target.length()} bytes.",
        )
    }

    private fun moveToTrash(args: JSONObject): ToolExecutionResult {
        val target = resolveWorkspacePath(requiredString(args, "path"))
        requireRegularTextFile(target)
        val original = relativePath(target)
        val safeName = original.replace('/', '_').replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        val trashName = "${System.currentTimeMillis()}__${safeName.ifBlank { "workspace_file" }}"
        val destination = File(trash, trashName)
        if (!target.renameTo(destination)) {
            target.copyTo(destination, overwrite = false)
            if (!target.delete()) {
                destination.delete()
                throw ToolValidationException("The workspace file could not be moved to the private trash safely.")
            }
        }
        File(trash, "$trashName.meta").writeText(original, StandardCharsets.UTF_8)
        removeEmptyParents(target.parentFile)
        return ToolExecutionResult(
            JSONObject()
                .put("status", "trashed")
                .put("original_path", original)
                .put("trash_name", trashName)
                .put("recoverable", true)
                .toString(),
            "Moved $original to recoverable workspace trash as $trashName.",
        )
    }

    private fun restoreFromTrash(args: JSONObject): ToolExecutionResult {
        val trashName = requiredString(args, "trash_name")
        if (!SAFE_TRASH_NAME.matches(trashName)) throw ToolValidationException("Invalid workspace trash name.")
        val source = File(trash, trashName).canonicalFile
        if (!source.path.startsWith(trash.path + File.separator) || !source.isFile) {
            throw ToolValidationException("Workspace trash item '$trashName' was not found.")
        }
        val metadata = File(trash, "$trashName.meta")
        val recordedPath = metadata.takeIf(File::isFile)?.readText(StandardCharsets.UTF_8)?.trim()
        val restorePath = optionalString(args, "restore_path") ?: recordedPath
            ?: throw ToolValidationException("No original path is recorded; provide restore_path.")
        val target = resolveWorkspacePath(restorePath, allowMissing = true)
        validateTextFileName(target)
        if (target.exists()) throw ToolValidationException("Restore destination already exists: ${relativePath(target)}")
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = false)
            if (!source.delete()) {
                target.delete()
                throw ToolValidationException("The trashed file could not be restored safely.")
            }
        }
        metadata.delete()
        return ToolExecutionResult(
            JSONObject().put("status", "restored").put("path", relativePath(target)).toString(),
            "Restored ${relativePath(target)} from workspace trash.",
        )
    }

    private fun resolveWorkspacePath(rawPath: String, allowMissing: Boolean = false): File {
        val normalized = rawPath.replace('\\', '/').trim().trim('/')
        if (normalized.isBlank() || normalized.length > MAX_WORKSPACE_PATH_CHARS) {
            throw ToolValidationException("A bounded relative workspace path is required.")
        }
        val segments = normalized.split('/').filter(String::isNotBlank)
        if (segments.any { it == "." || it == ".." || !SAFE_SEGMENT.matches(it) }) {
            throw ToolValidationException("Workspace paths may contain only safe relative filename segments.")
        }
        val target = File(root, segments.joinToString(File.separator)).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw ToolValidationException("Workspace path escaped the private sandbox.")
        }
        if (!allowMissing && !target.exists()) throw ToolValidationException("Workspace path was not found: $normalized")
        return target
    }

    private fun validateTextFileName(file: File) {
        if (file.name.startsWith('.')) throw ToolValidationException("Hidden workspace files are not allowed.")
        val extension = file.extension.lowercase(Locale.US)
        if (extension.isNotBlank() && extension !in ALLOWED_TEXT_EXTENSIONS) {
            throw ToolValidationException("Workspace files must use a recognized text or source-code extension.")
        }
    }

    private fun requireRegularTextFile(file: File) {
        if (!file.isFile) throw ToolValidationException("Workspace path is not a regular file: ${relativePath(file)}")
        validateTextFileName(file)
    }

    private fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    private fun allFiles(directory: File): List<File> = directory.walkTopDown()
        .filter(File::isFile)
        // Filtering by a requested prefix happens after this bounded scan. The previous
        // MAX_WORKSPACE_FILES + 1 cap could hide valid files behind unrelated entries,
        // causing list/search tool calls to report that an existing file did not exist.
        // Normal workspaces are capped at 250 files; the larger bound also repairs legacy
        // workspaces without allowing an unbounded traversal.
        .take(MAX_WORKSPACE_SCAN_FILES + 1)
        .toList()

    private fun removeEmptyParents(start: File?) {
        var current = start
        while (current != null && current != root && current.path.startsWith(root.path + File.separator)) {
            val children = current.listFiles()
            if (children != null && children.isEmpty()) current.delete() else break
            current = current.parentFile
        }
    }

    private fun parseArguments(raw: String): JSONObject = parseToolArguments(raw)

    private fun requiredString(args: JSONObject, name: String): String {
        val value = optionalString(args, name)
        if (value.isNullOrBlank()) throw ToolValidationException("Missing required workspace tool argument: $name.")
        return value
    }

    private fun requiredStringAllowEmpty(args: JSONObject, name: String): String {
        if (!args.has(name) || args.isNull(name)) throw ToolValidationException("Missing required workspace tool argument: $name.")
        return args.optString(name)
    }

    private fun optionalString(args: JSONObject, name: String): String? =
        args.optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }

    private companion object {
        val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}")
        val SAFE_TRASH_NAME = Regex("[A-Za-z0-9._-]{1,180}")
        val ALLOWED_TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "csv", "tsv", "html", "htm", "css", "js", "ts", "kt", "kts", "java",
            "xml", "yaml", "yml", "toml", "ini", "log", "sql", "py", "sh", "bat", "ps1", "gradle", "properties",
        )
    }
}
