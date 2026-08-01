package com.david.openassistant.handoff

import java.io.File

/**
 * Acceptance run for the Handoff Tool.
 * 
 * This is NOT a unit test and should not be annotated with @Test.
 * It is invoked by the verifyOverseerHandoffAcceptance Gradle task.
 */
class AcceptanceRun {
    fun runAcceptance(projectPath: String) {
        val root = File(projectPath).canonicalFile
        println("Project Root: ${root.absolutePath}")
        HandoffMain.main(arrayOf("generate", root.absolutePath))
    }
    
    fun runStability(projectPath: String) {
        val root = File(projectPath).canonicalFile
        HandoffMain.main(arrayOf("stability-test", root.absolutePath))
    }
}
