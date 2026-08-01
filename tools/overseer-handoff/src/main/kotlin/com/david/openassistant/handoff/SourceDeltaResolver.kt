package com.david.openassistant.handoff

import com.david.openassistant.handoff.model.SourceFileEntry
import org.json.JSONObject
import java.io.File

class SourceDeltaResolver(val projectRoot: File) {

    fun resolveDelta(currentEntries: List<SourceFileEntry>, parentManifestFile: File): DeltaResult {
        if (!parentManifestFile.exists()) {
            return DeltaResult(currentEntries, emptyList(), emptyList(), emptyList(), null)
        }

        val parentManifest = JSONObject(parentManifestFile.readText())
        val parentBundleId = parentManifest.getString("bundle_id")
        
        // OH-007: Use persisted inventory from state/
        val stateDir = parentManifestFile.parentFile
        val parentInventoryFile = File(stateDir, "last-verified-source-inventory.jsonl")
        
        if (!parentInventoryFile.exists()) {
            // Fallback to searching historical bundles if they exist (unlikely if cleanup works)
            println("WARNING: last-verified-source-inventory.jsonl not found in state/, searching historical bundles...")
            val parentDir = findBundleDir(parentBundleId)
            val bundleInventory = if (parentDir != null) File(parentDir, "project/source_inventory.jsonl") else null
            
            if (bundleInventory == null || !bundleInventory.exists()) {
                println("WARNING: Parent inventory not found. Falling back to SOURCE_BASELINE.")
                return DeltaResult(currentEntries, emptyList(), emptyList(), emptyList(), null)
            }
            bundleInventory.copyTo(parentInventoryFile, overwrite = true)
        }

        val parentEntries = parentInventoryFile.readLines()
            .filter { it.isNotBlank() }
            .map { JSONObject(it) }
            .associateBy { it.getString("path") }

        val added = mutableListOf<SourceFileEntry>()
        val modified = mutableListOf<SourceFileEntry>()
        val deletedPaths = parentEntries.keys.toMutableSet()
        val unchanged = mutableListOf<SourceFileEntry>()

        for (current in currentEntries) {
            val path = current.path
            val parent = parentEntries[path]
            if (parent == null) {
                added.add(current)
            } else {
                deletedPaths.remove(path)
                val parentSha = parent.optString("sha256", "")
                if (parentSha != current.sha256) {
                    modified.add(current)
                } else {
                    unchanged.add(current.copy(changed = false))
                }
            }
        }

        return DeltaResult(added, modified, unchanged, deletedPaths.toList(), parentBundleId)
    }

    private fun findBundleDir(bundleId: String): File? {
        val handoffDir = File(projectRoot, "overseer-handoff")
        return handoffDir.listFiles { f -> f.isDirectory && f.name.endsWith(bundleId) }?.firstOrNull()
    }

    data class DeltaResult(
        val added: List<SourceFileEntry>,
        val modified: List<SourceFileEntry>,
        val unchanged: List<SourceFileEntry>,
        val deletedPaths: List<String>,
        val parentBundleId: String?
    ) {
        val allChanged: List<SourceFileEntry> get() = added + modified
    }
}
