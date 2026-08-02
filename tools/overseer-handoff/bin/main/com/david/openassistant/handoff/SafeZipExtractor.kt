package com.david.openassistant.handoff

import java.io.File
import java.util.zip.ZipFile

class SafeZipExtractor(val extractionRoot: File) {

    private val MAX_ENTRIES = 50000
    private val MAX_TOTAL_SIZE = 1024L * 1024 * 1024 * 2 // 2GB
    private val MAX_COMPRESSION_RATIO = 100

    fun extract(zipFile: File) {
        val rootCanonical = extractionRoot.canonicalPath
        val normalizedPaths = mutableSetOf<String>()
        var totalSize = 0L
        var entryCount = 0

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().asSequence().toList()
            
            if (entries.size > MAX_ENTRIES) {
                throw SecurityException("ZIP extraction rejected: Excessive entry count (${entries.size})")
            }

            entries.forEach { entry ->
                entryCount++
                val name = entry.name
                
                // Rule 9: Reject unsafe paths
                if (name.contains('\u0000')) throw SecurityException("ZIP entry contains NUL character: $name")
                if (name.startsWith("/") || name.contains("..") || (name.contains(":") && !isWindowsDrive(name))) {
                     throw SecurityException("ZIP entry contains unsafe path: $name")
                }
                
                val targetFile = File(extractionRoot, name)
                val targetCanonical = targetFile.canonicalPath
                
                if (!targetCanonical.startsWith(rootCanonical + File.separator) && targetCanonical != rootCanonical) {
                    throw SecurityException("ZIP entry escapes extraction root: $name")
                }
                
                if (!normalizedPaths.add(targetCanonical)) {
                    throw SecurityException("ZIP entry contains duplicate normalized path: $name")
                }

                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    totalSize += entry.size
                    if (totalSize > MAX_TOTAL_SIZE) {
                        throw SecurityException("ZIP extraction rejected: Excessive total size (> 2GB)")
                    }
                    
                    if (entry.size > 0 && entry.compressedSize > 0) {
                        val ratio = entry.size / entry.compressedSize
                        if (ratio > MAX_COMPRESSION_RATIO) {
                            throw SecurityException("ZIP extraction rejected: Excessive compression ratio on $name")
                        }
                    }

                    targetFile.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun isWindowsDrive(name: String): Boolean {
        return name.length >= 2 && name[0].isLetter() && name[1] == ':'
    }
}
