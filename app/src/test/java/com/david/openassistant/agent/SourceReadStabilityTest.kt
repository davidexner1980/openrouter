package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SourceReadStabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = tempFolder.newFolder("agent_store_stability")
        store = AgentStore(tempDir)
    }

    @Test
    fun testSourceReadRedundancyAcrossDifferentAttempts() {
        val goalId = "goal-" + UUID.randomUUID()
        val taskId = "task-1"
        val canonicalUrl = "https://example.com/redundant"
        
        val claimId = "clm-1"
        val claim = AgentClaim(
            id = claimId,
            taskId = taskId,
            text = "Claim text",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(canonicalUrl),
            claimFingerprint = "fp1"
        )
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
            tasks = listOf(
                AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)
            ),
            leaseGeneration = 1,
            executionLease = AgentExecutionLease(
                workerId = "worker-1",
                ownerProcessSessionId = "session-1",
                taskId = taskId,
                attemptId = "attempt-1",
                generation = 1,
                acquiredAt = System.currentTimeMillis(),
                heartbeatAt = System.currentTimeMillis()
            ),
            claims = listOf(claim)
        )
        
        store.upsertGoal(goal)
        val ticket = TaskExecutionTicket(goalId, taskId, "worker-1", "session-1", 1, "attempt-1", System.currentTimeMillis())

        // 1. Attempt 1 claims and commits
        val claim1 = store.claimSourceFetchAtomic(ticket, taskId, canonicalUrl, "fp-1")
        assertTrue(claim1 is SourceFetchClaimResult.Claimed)
        val claim1Id = (claim1 as SourceFetchClaimResult.Claimed).attempt.id
        
        val content = "Content 1"
        val contentHash = FingerprintUtils.hash(content)
        val sourceRead1 = SourceRead(
            id = scopedSourceReadId(canonicalUrl, contentHash),
            url = canonicalUrl,
            canonicalUrl = canonicalUrl,
            documentId = "doc-1",
            contentHash = contentHash,
            httpCode = 200,
            contentType = "text/html",
            content = content,
            sourceRole = "research",
            authorityScore = 9,
            provenance = SourceReadProvenance.VERIFIED_FETCH
        )
        val commit1 = store.commitSourceReadAtomic(ticket, claim1Id, sourceRead1, AgentToolExecution("tool", "Fetch 1", true))
        assertTrue(commit1 is RecordSourceReadResult.Persisted)

        // 2. Attempt 2 (maybe a retry or different pass) claims and commits the SAME URL
        val claim2 = store.claimSourceFetchAtomic(ticket, taskId, canonicalUrl, "fp-2")
        assertTrue(claim2 is SourceFetchClaimResult.Claimed)
        val claim2Id = (claim2 as SourceFetchClaimResult.Claimed).attempt.id
        
        val content2 = "Content 1" // Identical content
        val contentHash2 = FingerprintUtils.hash(content2)
        val sourceRead2 = SourceRead(
            id = scopedSourceReadId(canonicalUrl, contentHash2),
            url = canonicalUrl,
            canonicalUrl = canonicalUrl,
            documentId = "doc-1",
            contentHash = contentHash2,
            httpCode = 200,
            contentType = "text/html",
            content = content2,
            sourceRole = "research",
            authorityScore = 9,
            provenance = SourceReadProvenance.VERIFIED_FETCH
        )
        val commit2 = store.commitSourceReadAtomic(ticket, claim2Id, sourceRead2, AgentToolExecution("tool", "Fetch 2", true))
        
        // REPRODUCTION: Currently this returns Persisted and adds a 2nd entry to goal.sourceReads
        assertTrue("Expected second commit to be ReusedExisting", commit2 is RecordSourceReadResult.ReusedExisting)

        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Should have only 1 unique source read for the same URL", 1, finalGoal.sourceReads.size)
    }
}
