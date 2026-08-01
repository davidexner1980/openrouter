package com.david.openassistant.data.diagnostics

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class PublicExportTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `outbox copy matches canonical source`() {
        val canonicalReport = tempFolder.newFile("canonical.md")
        val content = "# Research Report\nSession: 123"
        canonicalReport.writeText(content)
        val expectedSha = sha256(canonicalReport)
        val expectedBytes = canonicalReport.length()

        val outboxDir = tempFolder.newFolder("outbox")
        val operationId = "test-op-1"
        val outboxFile = File(outboxDir, "$operationId.md")
        
        canonicalReport.copyTo(outboxFile)

        assertEquals(expectedBytes, outboxFile.length())
        assertEquals(expectedSha, sha256(outboxFile))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
