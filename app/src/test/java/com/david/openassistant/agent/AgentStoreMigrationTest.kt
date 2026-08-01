package com.david.openassistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentStoreMigrationTest {

    @Test
    fun version7GoalJsonDecodesSafelyWithNewVersion8Fields() {
        val goalId = "goal-" + UUID.randomUUID()
        val v7Json = JSONObject()
            .put("storage_version", 7)
            .put("id", goalId)
            .put("conversation_id", "conv-1")
            .put("user_request", "Analyze high end bows")
            .put("title", "Bow Analysis")
            .put("objective", "Analyze bows in detail")
            .put("final_output_description", "Report")
            .put("status", "QUEUED")
            .put("planner_model_id", "openrouter/auto-beta")
            .put("execution_model_id", "openrouter/auto-beta")
            .put("routing_stage", "AUTO_BETA")
            .put("total_cost_usd", 0.005)
            .put("tasks", org.json.JSONArray())

        val tempDir = java.nio.file.Files.createTempDirectory("agentstore_mig_test").toFile()
        val store = AgentStore(tempDir)
        val decodeMethod = AgentStore::class.java.getDeclaredMethod("decodeGoal", JSONObject::class.java)
        decodeMethod.isAccessible = true
        val decodedGoal = decodeMethod.invoke(store, v7Json) as AgentGoal

        assertEquals(5000L, decodedGoal.totalCostUsdMicros)
        assertEquals(RoutingPolicyProvenance.LEGACY_EXPLICIT, decodedGoal.routingPolicyProvenance)
        assertEquals(false, decodedGoal.freeOnly)
    }

    @Test
    fun version7FreeRouteMigratesToSafetyLock() {
        val v7Json = JSONObject()
            .put("storage_version", 7)
            .put("id", "goal-free")
            .put("conversation_id", "conv-1")
            .put("user_request", "Free research")
            .put("status", "QUEUED")
            .put("routing_stage", "FREE")
            .put("planner_model_id", "openrouter/free")
            .put("execution_model_id", "openrouter/free")

        val tempDir = java.nio.file.Files.createTempDirectory("agentstore_free_mig").toFile()
        val store = AgentStore(tempDir)
        val decodeMethod = AgentStore::class.java.getDeclaredMethod("decodeGoal", JSONObject::class.java)
        decodeMethod.isAccessible = true
        val decodedGoal = decodeMethod.invoke(store, v7Json) as AgentGoal

        assertEquals(true, decodedGoal.freeOnly)
        assertEquals(RoutingPolicyProvenance.LEGACY_AMBIGUOUS_SAFETY_LOCK, decodedGoal.routingPolicyProvenance)
    }

    @Test
    fun version7CostUsdFixtureDecodesAndReEncodesSafely() {
        val inputStream = javaClass.classLoader!!.getResourceAsStream("fixtures/agent_goal_v7_cost_usd.json")
        assertNotNull("Fixture agent_goal_v7_cost_usd.json not found", inputStream)
        val rawJsonText = inputStream!!.bufferedReader().use { it.readText() }
        val rawJsonObject = JSONObject(rawJsonText)

        val tempDir = java.nio.file.Files.createTempDirectory("agentstore_test").toFile()
        val store = AgentStore(tempDir)
        
        // Decode attempt directly via AgentStore's decode method
        val decodeMethod = AgentStore::class.java.getDeclaredMethod("decodeGoal", JSONObject::class.java)
        decodeMethod.isAccessible = true
        val decodedGoal = decodeMethod.invoke(store, rawJsonObject) as AgentGoal

        assertEquals(1, decodedGoal.requestAttempts.size)
        val attempt = decodedGoal.requestAttempts.first()
        assertEquals(0.012345, attempt.costUsd!!, 1e-6)
        assertEquals("goal-v7-usd-1", attempt.goalId)
        assertEquals("PRIMARY_REASONING", attempt.role?.name)

        // Re-encode via AgentStore
        val encodeMethod = AgentStore::class.java.getDeclaredMethod("encodeGoal", AgentGoal::class.java)
        encodeMethod.isAccessible = true
        val reEncodedJson = encodeMethod.invoke(store, decodedGoal) as JSONObject

        val reEncodedAttempts = reEncodedJson.getJSONArray("request_attempts")
        val reEncodedAttemptObj = reEncodedAttempts.getJSONObject(0)

        assertTrue(reEncodedAttemptObj.has("cost_usd"))
        assertEquals(0.012345, reEncodedAttemptObj.getDouble("cost_usd"), 1e-6)
        assertTrue(!reEncodedAttemptObj.has("cost_usd_micros") || reEncodedAttemptObj.isNull("cost_usd_micros"))

        // Reload the re-encoded json and assert semantic equality
        val reDecodedGoal = decodeMethod.invoke(store, reEncodedJson) as AgentGoal
        assertEquals(decodedGoal.id, reDecodedGoal.id)
        assertEquals(decodedGoal.requestAttempts.size, reDecodedGoal.requestAttempts.size)
        assertEquals(decodedGoal.requestAttempts.first().costUsd, reDecodedGoal.requestAttempts.first().costUsd)
    }

    @Test
    fun version7CostUsdMicrosContaminatedFixtureFallbackDecodesAndReEncodesSafely() {
        val inputStream = javaClass.classLoader!!.getResourceAsStream("fixtures/agent_goal_v7_cost_usd_micros.json")
        assertNotNull("Fixture agent_goal_v7_cost_usd_micros.json not found", inputStream)
        val rawJsonText = inputStream!!.bufferedReader().use { it.readText() }
        val rawJsonObject = JSONObject(rawJsonText)

        val tempDir = java.nio.file.Files.createTempDirectory("agentstore_test2").toFile()
        val store = AgentStore(tempDir)

        val decodeMethod = AgentStore::class.java.getDeclaredMethod("decodeGoal", JSONObject::class.java)
        decodeMethod.isAccessible = true
        val decodedGoal = decodeMethod.invoke(store, rawJsonObject) as AgentGoal

        assertEquals(1, decodedGoal.requestAttempts.size)
        val attempt = decodedGoal.requestAttempts.first()
        assertEquals(0.012345, attempt.costUsd!!, 1e-6)

        // Re-encode via AgentStore
        val encodeMethod = AgentStore::class.java.getDeclaredMethod("encodeGoal", AgentGoal::class.java)
        encodeMethod.isAccessible = true
        val reEncodedJson = encodeMethod.invoke(store, decodedGoal) as JSONObject

        val reEncodedAttempts = reEncodedJson.getJSONArray("request_attempts")
        val reEncodedAttemptObj = reEncodedAttempts.getJSONObject(0)

        assertTrue(reEncodedAttemptObj.has("cost_usd"))
        assertEquals(0.012345, reEncodedAttemptObj.getDouble("cost_usd"), 1e-6)
        assertTrue(!reEncodedAttemptObj.has("cost_usd_micros") || reEncodedAttemptObj.isNull("cost_usd_micros"))
    }

    @Test
    fun legacyTaskJsonDecodesSafelyWithNewConvergenceFields() {
        val taskJson = JSONObject()
            .put("id", "task-legacy")
            .put("order", 0)
            .put("title", "Legacy Task")
            .put("instructions", "Do research")
            .put("capability", "WEB_RESEARCH")
            .put("status", "QUEUED")

        val tempDir = java.nio.file.Files.createTempDirectory("agentstore_task_test").toFile()
        val store = AgentStore(tempDir)
        val decodeMethod = AgentStore::class.java.getDeclaredMethod("decodeTask", JSONObject::class.java)
        decodeMethod.isAccessible = true
        val decodedTask = decodeMethod.invoke(store, taskJson) as AgentTask

        assertEquals(0, decodedTask.globalAutomaticWindowReopenCount)
        assertNull(decodedTask.progressFingerprint)
        assertTrue(decodedTask.queryFingerprints.isEmpty())
    }
}
