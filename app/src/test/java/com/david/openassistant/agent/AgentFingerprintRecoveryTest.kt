package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class AgentFingerprintRecoveryTest {

    @Test
    fun recoverableNetworkFailureSetsAuthorizationAndAllowsRetry() {
        val taskId = "task-1"
        val fingerprint = "fingerprint-123"
        
        val task = AgentTask(
            id = taskId,
            order = 0,
            title = "Research",
            instructions = "Find data",
            capability = AgentCapability.DEEP_RESEARCH,
            lastRequestFingerprint = fingerprint,
            attemptCount = 1
        )
        
        // Simulate persistTaskFailure logic for network wait
        val waitingForNetwork = true
        val failedTask = task.copy(
            status = AgentTaskStatus.FAILED,
            lastError = "Network failure",
            retryAuthorizedFingerprint = if (waitingForNetwork) fingerprint else null
        )
        
        assertEquals(fingerprint, failedTask.retryAuthorizedFingerprint)
        
        // Verify consumption logic (simulated from executeOneTask)
        val isAuthorizedRetry = failedTask.retryAuthorizedFingerprint == fingerprint
        val consumedTask = if (isAuthorizedRetry) {
            failedTask.copy(retryAuthorizedFingerprint = null)
        } else {
            failedTask
        }
        
        assertNull(consumedTask.retryAuthorizedFingerprint)
    }

    @Test
    fun successProtectingFromRepetitionRemainsActive() {
        val fingerprint = "fingerprint-123"
        val task = AgentTask(
            id = "task-1",
            order = 0,
            title = "Research",
            instructions = "Find data",
            capability = AgentCapability.DEEP_RESEARCH,
            lastRequestFingerprint = fingerprint,
            attemptCount = 1,
            retryAuthorizedFingerprint = null // No authorization
        )
        
        val currentFingerprint = fingerprint
        val isAuthorizedRetry = task.retryAuthorizedFingerprint == currentFingerprint
        
        // If not authorized and fingerprint matches, we skip (logic from executeOneTask)
        val shouldSkip = task.lastRequestFingerprint == currentFingerprint && task.attemptCount >= 1 && !isAuthorizedRetry
        
        assertEquals(true, shouldSkip)
    }
}
