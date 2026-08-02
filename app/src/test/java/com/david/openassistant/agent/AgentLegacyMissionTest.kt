package com.david.openassistant.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class AgentLegacyMissionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private val goalId = "legacy-goal"

    private lateinit var storeDir: File

    @Before
    fun setup() {
        storeDir = tempFolder.newFolder()
        store = AgentStore(storeDir)
    }

    @Test
    fun legacyLeaseReclamation_MissingSessionId_ReclaimsSuccessfully() {
        // 1. Manually write a legacy goal file with missing owner_process_session_id
        val goalsDir = File(storeDir, "agent_runtime_v2/goals")
        goalsDir.mkdirs()
        val goalFile = File(goalsDir, "legacy_goal.goal.json") // Match sanitized ID
        
        val legacyJson = JSONObject()
            .put("id", goalId)
            .put("conversation_id", "conv-1")
            .put("user_request", "test")
            .put("title", "Title")
            .put("objective", "obj")
            .put("status", "RUNNING")
            .put("planner_model_id", "m1")
            .put("execution_model_id", "m1")
            .put("tasks", org.json.JSONArray())
            .put("lease_generation", 1)
            .put("updated_at", System.currentTimeMillis())
            .put("execution_lease", JSONObject()
                .put("worker_id", "old-worker")
                .put("task_id", "task-1")
                .put("attempt_id", "old-attempt")
                .put("generation", 1)
                .put("acquired_at", System.currentTimeMillis())
                .put("heartbeat_at", System.currentTimeMillis())
                // owner_process_session_id is MISSING
            )

        goalFile.writeText(legacyJson.toString(), StandardCharsets.UTF_8)

        // 2. Attempt acquisition
        val result = store.acquireTaskLeaseAtomic(goalId, "new-worker", "task-1")
        
        if (result !is LeaseAcquisitionResult.OrphanReclaimed && result !is LeaseAcquisitionResult.Acquired) {
            val snapshot = store.loadSnapshot()
            val present = snapshot.goals.any { it.id == goalId }
            fail("Expected acquisition or reclamation, but got: $result (Goal present in store: $present)")
        }
        
        val goal = if (result is LeaseAcquisitionResult.OrphanReclaimed) result.goal else (result as LeaseAcquisitionResult.Acquired).goal
        assertEquals("new-worker", goal.executionLease?.workerId)
        assertNotEquals("unknown", goal.executionLease?.ownerProcessSessionId)
        assertFalse(goal.executionLease?.ownerProcessSessionId.isNullOrBlank())
    }
}
