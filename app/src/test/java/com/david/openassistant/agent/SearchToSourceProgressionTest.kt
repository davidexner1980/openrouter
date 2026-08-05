package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SearchToSourceProgressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = tempFolder.newFolder("agent_store_search")
        store = AgentStore(tempDir)
    }

    @Test
    fun testTwoPhaseFetchIdentityAndPersistence() {
        val goalId = "goal-" + UUID.randomUUID()
        val taskId = "task-1"
        val canonicalUrl = "https://example.com/research"
        val fetchFingerprint = "fp-123"
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Request",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Description",
            status = AgentGoalStatus.RESEARCHING,
            plannerModelId = "planner",
            executionModelId = "executor",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)),
            leaseGeneration = 1,
            executionLease = AgentExecutionLease(
                workerId = "worker-1",
                ownerProcessSessionId = "session-1",
                taskId = taskId,
                attemptId = "attempt-1",
                generation = 1,
                acquiredAt = System.currentTimeMillis(),
                heartbeatAt = System.currentTimeMillis()
            )
        )
        
        store.upsertGoal(goal)
        val ticket = TaskExecutionTicket(goalId, taskId, "worker-1", "session-1", 1, "attempt-1", System.currentTimeMillis())

        // 1. Claim Fetch
        val claimResult = store.claimSourceFetchAtomic(ticket, taskId, canonicalUrl, fetchFingerprint)
        assertTrue(claimResult is SourceFetchClaimResult.Claimed)
        val fetchClaimId = (claimResult as SourceFetchClaimResult.Claimed).attempt.id
        
        // 2. Restart/Duplicate Claim returns ReusedExisting
        val duplicateClaim = store.claimSourceFetchAtomic(ticket, taskId, canonicalUrl, fetchFingerprint)
        assertTrue(duplicateClaim is SourceFetchClaimResult.ReusedExisting)

        // 3. Commit Source Read
        val sourceRead = SourceRead(
            id = UUID.randomUUID().toString(),
            url = canonicalUrl,
            canonicalUrl = canonicalUrl,
            httpCode = 200,
            contentType = "text/html",
            content = "Substantive content here",
            sourceRole = "research",
            authorityScore = 9,
            provenance = SourceReadProvenance.VERIFIED_FETCH
        )
        val toolExecution = AgentToolExecution(
            toolName = "public_web_fetch",
            summary = "Fetched source",
            succeeded = true
        )
        
        val commitResult = store.commitSourceReadAtomic(ticket, fetchClaimId, sourceRead, toolExecution)
        assertTrue(commitResult is RecordSourceReadResult.Persisted)
        
        // 4. Duplicate Commit returns ReusedExisting
        val duplicateCommit = store.commitSourceReadAtomic(ticket, fetchClaimId, sourceRead, toolExecution)
        assertTrue(duplicateCommit is RecordSourceReadResult.ReusedExisting)

        // Exact counts assertion
        val updatedGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("fetch claims", 1, updatedGoal.fetchAttempts.size)
        assertEquals("durable SourceReads", 1, updatedGoal.sourceReads.size)
        assertEquals("tool accounting", 1, updatedGoal.toolExecutions.size)
    }
}
