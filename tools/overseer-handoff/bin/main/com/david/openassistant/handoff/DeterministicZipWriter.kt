package com.david.openassistant.handoff

import java.io.File
import java.io.FileOutputStream
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DeterministicZipWriter {

    fun createZip(sourceDir: File, zipFile: File) {
        val files = sourceDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(sourceDir).path.replace("\\", "/") }
            .toList()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // Use a fixed timestamp for determinism
            val fixedTime = FileTime.fromMillis(0)
            
            for (file in files) {
                val relativePath = file.relativeTo(sourceDir).path.replace("\\", "/")
                val entry = ZipEntry(relativePath).apply {
                    lastModifiedTime = fixedTime
                    lastAccessTime = fixedTime
                    creationTime = fixedTime
                }
                zos.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
