package com.david.openassistant.handoff

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.File
import java.nio.file.Files

class TaskBootstrapTest {
    @Test
    fun testVersionOutput() {
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        
        HandoffMain.runCommand(arrayOf("--version"))
        
        assertTrue(outContent.toString().contains("1.2.0"))
    }

    @Test
    fun testCommandParsing() {
        val outContent = ByteArrayOutputStream()
        System.setOut(PrintStream(outContent))
        
        // Use a temporary directory for the test to avoid lock file conflicts and polluting the project
        val tempDir = Files.createTempDirectory("handoff-test-").toFile()
        try {
            // Execution might fail due to empty directory, but we check if it started
            try {
                HandoffMain.runCommand(arrayOf("generate", tempDir.absolutePath))
            } catch (e: Exception) {}
            
            val output = outContent.toString()
            assertTrue(output.contains("Command: generate"))
            // It should at least start generating
            assertTrue(output.contains("Generating Overseer Handoff Bundle..."))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
