package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SourceReadIdentityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = tempFolder.newFolder("agent_store_identity")
        store = AgentStore(tempDir)
    }

    private fun createGoal(goalId: String, taskId: String): AgentGoal {
        val claim = AgentClaim(
            id = "c1",
            taskId = taskId,
            text = "Claim",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            claimFingerprint = "fp1"
        )
        return AgentGoal(
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
    }

    private fun createTaskTicket(goalId: String, taskId: String) = TaskExecutionTicket(
        goalId = goalId,
        taskIdentity = taskId,
        workerId = "worker-1",
        ownerProcessSessionId = "session-1",
        generation = 1,
        attemptId = "attempt-1",
        acquiredAt = System.currentTimeMillis()
    )

    @Test
    fun `test identical retrieval Law 2`() {
        val goalId = "goal-" + UUID.randomUUID()
        val taskId = "task-1"
        val url = "https://example.com/law2"
        val content = "Same Content"
        
        store.upsertGoal(createGoal(goalId, taskId))
        val ticket = createTaskTicket(goalId, taskId)

        val claim1 = store.claimSourceFetchAtomic(ticket, taskId, url, "fp-1") as SourceFetchClaimResult.Claimed
        val sourceRead1 = createSourceRead(url, content, SourceReadProvenance.VERIFIED_FETCH)
        val commit1 = store.commitSourceReadAtomic(ticket, claim1.attempt.id, sourceRead1, AgentToolExecution("fetch", "call 1", true))
        assertTrue(commit1 is RecordSourceReadResult.Persisted)

        val claim2 = store.claimSourceFetchAtomic(ticket, taskId, url, "fp-2") as SourceFetchClaimResult.Claimed
        val sourceRead2 = createSourceRead(url, content, SourceReadProvenance.VERIFIED_FETCH)
        val commit2 = store.commitSourceReadAtomic(ticket, claim2.attempt.id, sourceRead2, AgentToolExecution("fetch", "call 2", true))
        assertTrue(commit2 is RecordSourceReadResult.ReusedExisting)

        val goal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Should have exactly one snapshot", 1, goal.sourceReads.size)
        assertEquals("Should have exactly two tool-call fetch attempts", 2, goal.fetchAttempts.size)
    }

    @Test
    fun `test changed resource Law 2`() {
        val goalId = "goal-" + UUID.randomUUID()
        val taskId = "task-1"
        val url = "https://example.com/law2-changed"
        
        store.upsertGoal(createGoal(goalId, taskId))
        val ticket = createTaskTicket(goalId, taskId)

        val claim1 = store.claimSourceFetchAtomic(ticket, taskId, url, "fp-1") as SourceFetchClaimResult.Claimed
        val sourceRead1 = createSourceRead(url, "Content Version A", SourceReadProvenance.VERIFIED_FETCH)
        store.commitSourceReadAtomic(ticket, claim1.attempt.id, sourceRead1, AgentToolExecution("fetch", "call 1", true))

        val claim2 = store.claimSourceFetchAtomic(ticket, taskId, url, "fp-2") as SourceFetchClaimResult.Claimed
        val sourceRead2 = createSourceRead(url, "Content Version B", SourceReadProvenance.VERIFIED_FETCH)
        val commit2 = store.commitSourceReadAtomic(ticket, claim2.attempt.id, sourceRead2, AgentToolExecution("fetch", "call 2", true))
        assertTrue(commit2 is RecordSourceReadResult.Persisted)

        val goal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Should have two immutable snapshots", 2, goal.sourceReads.size)
        assertNotEquals("Content hashes must be different", goal.sourceReads[0].contentHash, goal.sourceReads[1].contentHash)
    }

    @Test
    fun `test provenance upgrade Law 3`() {
        val url = "https://upgrade.com"
        val content = "Shared Data"
        val read1 = createSourceRead(url, content, SourceReadProvenance.UNVERIFIED_CITATION)
        val read2 = createSourceRead(url, content, SourceReadProvenance.VERIFIED_FETCH)
        
        val merged = mergeSourceReads(listOf(read1), listOf(read2))
        assertEquals(1, merged.size)
        assertEquals("Verified snapshot should become eligible", SourceReadProvenance.VERIFIED_FETCH, merged[0].provenance)
    }

    @Test
    fun `test provenance downgrade attempt Law 4`() {
        val url = "https://downgrade.com"
        val content = "Protected Data"
        val read1 = createSourceRead(url, content, SourceReadProvenance.VERIFIED_FETCH)
        val read2 = createSourceRead(url, content, SourceReadProvenance.UNVERIFIED_CITATION)
        
        val merged = mergeSourceReads(listOf(read1), listOf(read2))
        assertEquals(1, merged.size)
        assertEquals("Verified snapshot remains authoritative", SourceReadProvenance.VERIFIED_FETCH, merged[0].provenance)
    }

    @Test
    fun `test incoming-batch collision`() {
        val url = "https://collision.com"
        val content = "Data"
        val read1 = createSourceRead(url, content, SourceReadProvenance.UNVERIFIED_CITATION)
        val read2 = createSourceRead(url, content, SourceReadProvenance.VERIFIED_FETCH)
        
        val merged1 = mergeSourceReads(emptyList(), listOf(read1, read2))
        val merged2 = mergeSourceReads(emptyList(), listOf(read2, read1))
        
        assertEquals("Deterministic resolution: verified should win order 1", SourceReadProvenance.VERIFIED_FETCH, merged1[0].provenance)
        assertEquals("Deterministic resolution: verified should win order 2", SourceReadProvenance.VERIFIED_FETCH, merged2[0].provenance)
    }

    @Test
    fun `test process death idempotency`() {
        val goalId = "goal-death"
        val taskId = "task-1"
        val url = "https://example.com/death"
        val content = "Vital Data"
        
        store.upsertGoal(createGoal(goalId, taskId))
        val ticket = createTaskTicket(goalId, taskId)

        val claim = store.claimSourceFetchAtomic(ticket, taskId, url, "fp-1") as SourceFetchClaimResult.Claimed
        val sourceRead = createSourceRead(url, content, SourceReadProvenance.VERIFIED_FETCH)
        store.commitSourceReadAtomic(ticket, claim.attempt.id, sourceRead, AgentToolExecution("fetch", "call", true))

        // Create a binding
        val bindingIdentity = CitationBindingIdentity(
            schemaVersion = 1,
            claimFingerprint = "fp1",
            sourceReadId = sourceRead.id,
            documentId = sourceRead.documentId,
            contentHash = sourceRead.contentHash,
            passageHash = FingerprintUtils.hash(content),
            bindingMethod = CitationBindingMethod.EXACT
        )
        val fp = calculateCitationBindingFingerprint(bindingIdentity)
        val binding = CitationBinding(
            id = scopedCitationBindingId(fp),
            claimId = "c1",
            sourceReadId = sourceRead.id,
            documentId = sourceRead.documentId,
            contentHash = sourceRead.contentHash,
            citationExcerpt = content,
            passageStart = 0,
            passageEnd = content.length,
            passageHash = bindingIdentity.passageHash,
            bindingMethod = CitationBindingMethod.EXACT,
            identitySchemaVersion = 1,
            logicalFingerprint = fp
        )

        // Add claim with binding
        val updatedGoal = store.loadSnapshot().goals.first { it.id == goalId }.copy(
            claims = listOf(
                AgentClaim(
                    id = "c1",
                    taskId = taskId,
                    text = content, // Use content to ensure alignment passes
                    type = AgentClaimType.FACT,
                    confidence = 1.0,
                    support = AgentClaimSupport.SUPPORTED,
                    sourceUrls = listOf(url),
                    citationBindings = listOf(binding),
                    claimFingerprint = "fp1"
                )
            )
        )
        store.upsertGoal(updatedGoal)

        val store2 = AgentStore(tempDir)
        val goal2 = store2.loadSnapshot().goals.first { it.id == goalId }
        
        assertEquals("Should have exactly one snapshot", 1, goal2.sourceReads.size)
        assertEquals("Should have exactly one claim", 1, goal2.claims.size)
        assertEquals("Should have exactly one binding", 1, goal2.claims[0].citationBindings.size)
        assertEquals("Binding ID must be stable", binding.id, goal2.claims[0].citationBindings[0].id)
        assertEquals(AgentClaimSupport.SUPPORTED, goal2.claims[0].support)
    }

    @Test
    fun `test source tampering recomputation`() {
        val url = "https://tamper.com"
        val originalContent = "Original Content"
        val originalHash = FingerprintUtils.hash(originalContent)
        
        val tamperedRead = SourceRead(
            id = scopedSourceReadId(url, originalHash),
            url = url,
            canonicalUrl = url,
            documentId = scopedSourceDocumentId(url),
            contentHash = originalHash,
            httpCode = 200,
            contentType = "text/plain",
            content = "Tampered Content", // Actual content changed
            sourceRole = "research",
            authorityScore = 10,
            provenance = SourceReadProvenance.VERIFIED_FETCH
        )

        val binding = CitationBinding.createLegacy(
            claimId = "c1",
            sourceReadId = tamperedRead.id,
            documentId = tamperedRead.documentId,
            contentHash = originalHash,
            citationExcerpt = "Content",
            passageStart = 9,
            passageEnd = 16,
            passageHash = FingerprintUtils.hash("Content"),
            bindingMethod = CitationBindingMethod.EXACT
        )
        
        val claim = AgentClaim(
            id = "c1",
            taskId = "task-1",
            text = "Claim",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            citationBindings = listOf(binding),
            claimFingerprint = "fp1"
        )

        val decision = FactualClaimSupportPolicy.evaluate(claim, listOf(tamperedRead))
        assertTrue("Must detect tampering via hash recomputation", decision is FactualClaimSupportDecision.Unsupported)
        val unsupported = decision as FactualClaimSupportDecision.Unsupported
        assertTrue("Reason should indicate hash mismatch", unsupported.reasons.contains("source_content_hash_mismatch"))
    }

    @Test
    fun `test logical document identity stability Law 2`() {
        val url = "https://example.com/lineage"
        val read1 = createSourceRead(url, "Content A", SourceReadProvenance.VERIFIED_FETCH)
        val read2 = createSourceRead(url, "Content B", SourceReadProvenance.VERIFIED_FETCH)
        
        assertNotEquals("SourceRead IDs must differ due to content change", read1.id, read2.id)
        assertEquals("Document IDs must be same for the same URL lineage", read1.documentId, read2.documentId)
        assertTrue("Document ID should be based on URL", read1.documentId.startsWith("doc_"))
    }

    private fun createSourceRead(url: String, content: String, provenance: SourceReadProvenance): SourceRead {
        val hash = FingerprintUtils.hash(content)
        val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(url)
        return SourceRead(
            id = scopedSourceReadId(canonicalUrl, hash),
            url = url,
            canonicalUrl = canonicalUrl,
            documentId = scopedSourceDocumentId(canonicalUrl),
            contentHash = hash,
            httpCode = 200,
            contentType = "text/plain",
            content = content,
            sourceRole = "research",
            authorityScore = 10,
            provenance = provenance
        )
    }
}
