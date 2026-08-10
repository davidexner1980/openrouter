package com.david.openassistant.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*

class ContinuationGenerationTruthTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private val goalId = "goal-1"

    @Before
    fun setup() {
        store = AgentStore(baseDir = tempFolder.newFolder("agent_store_test"))
    }

    @Test
    fun testContinuationClaimDistinguishesGenerations() = runBlocking {
        val executionGeneration = 2
        val leaseGeneration = 17
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Req",
            title = "Title",
            objective = "Obj",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList(),
            executionGeneration = executionGeneration,
            leaseGeneration = leaseGeneration
        )
        store.upsertGoal(goal, true)

        val fingerprint = "continuation-fp"
        val workName = "unique-work-name"
        
        // 1. Claim continuation using leaseGeneration as the fence
        val claim = store.claimContinuationAtomic(
            goalId = goalId,
            fingerprint = fingerprint,
            claimantLeaseGeneration = leaseGeneration,
            workName = workName
        )
        
        assertNotNull(claim)
        assertEquals(leaseGeneration, claim?.claimantGeneration)
        assertEquals(fingerprint, claim?.continuationFingerprint)
        assertEquals(ContinuationSchedulingState.PENDING, claim?.state)

        // 2. Verify we can't claim with a stale lease generation
        store.claimContinuationAtomic(
            goalId = goalId,
            fingerprint = "different-fp",
            claimantLeaseGeneration = leaseGeneration - 1,
            workName = workName
        )
        // Currently AgentStore.claimContinuationAtomic allows resuming pending if claimantGeneration matches.
        // If it doesn't match, it should return null or the existing claim if it's already active.
        // Let's check existing implementation:
        // if (existing.state == ContinuationSchedulingState.PENDING && existing.claimantGeneration == generation) { return existing }
        // Otherwise it creates a NEW one and overwrites.
        // Wait, if it overwrites, is that safe? 
        // "V4 should explicitly establish: Worker ownership fence -> leaseGeneration"
        
        val reloaded = store.loadSnapshot().goals.first { it.id == goalId }
        assertNotNull(reloaded.activeContinuationSchedulingClaim)
        assertEquals(leaseGeneration, reloaded.activeContinuationSchedulingClaim?.claimantGeneration)
    }

    @Test
    fun testUniqueWorkNameIncludesExecutionGeneration() {
        val exGen = 3
        val name = AgentScheduler.uniqueWorkName(goalId, exGen)
        assertTrue(name.contains(goalId))
        assertTrue(name.contains("exgen_$exGen"))
    }
}
