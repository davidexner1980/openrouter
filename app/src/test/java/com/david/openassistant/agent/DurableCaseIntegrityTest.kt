package com.david.openassistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class DurableCaseIntegrityTest {

    @Test
    fun snapshotSaveIsMergeOnlyAndCannotDeleteAnExistingGoal() {
        val root = Files.createTempDirectory("agent-store-merge").toFile()
        val store = AgentStore(root)
        val first = goal("goal-first", "Find the original question exactly")
        val second = goal("goal-second", "Preserve this independent mission")

        store.upsertGoal(first, select = true)
        store.upsertGoal(second, select = false)
        store.saveSnapshot(AgentSnapshot(goals = listOf(first), selectedGoalId = first.id))

        val reloaded = AgentStore(root).loadSnapshot()
        assertEquals(setOf(first.id, second.id), reloaded.goals.map { it.id }.toSet())
    }

    @Test
    fun blankOriginalRequestIsRejectedBeforeGoalPersistence() {
        val root = Files.createTempDirectory("agent-store-empty-request").toFile()
        val store = AgentStore(root)
        val invalid = goal("goal-invalid", "").copy(status = AgentGoalStatus.PLANNING)

        try {
            store.upsertGoal(invalid, select = true)
            fail("Blank original requests must not be persisted as runnable goals.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("original user request", ignoreCase = true))
        }
        assertTrue(store.loadSnapshot().goals.isEmpty())
    }

    @Test
    fun parseableGoalWithMissingRequestBecomesNonRunnableCorruptMission() {
        val root = Files.createTempDirectory("agent-store-corrupt").toFile()
        val store = AgentStore(root)
        val encode = AgentStore::class.java.getDeclaredMethod("encodeGoal", AgentGoal::class.java).apply {
            isAccessible = true
        }
        val decode = AgentStore::class.java.getDeclaredMethod("decodeGoal", JSONObject::class.java).apply {
            isAccessible = true
        }

        val encoded = encode.invoke(store, goal("goal-corrupt", "Valid request before corruption")) as JSONObject
        encoded.put("user_request", "")
        encoded.put("title", "")

        val decoded = decode.invoke(store, encoded) as AgentGoal
        assertEquals(AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, decoded.status)
        assertTrue(decoded.isCorrupt)
        assertTrue(decoded.error.orEmpty().contains("original user request", ignoreCase = true))
        assertFalse(decoded.title == "Automated goal")
        assertFalse(decoded.userRequest == "No conversation provided.")
    }

    @Test
    fun malformedGoalFileIsReportedAsQuarantinedInsteadOfSilentlyDisappearing() {
        val root = Files.createTempDirectory("agent-store-quarantine").toFile()
        val goalsDirectory = root.resolve("agent_runtime_v2/goals").apply { mkdirs() }
        val broken = goalsDirectory.resolve("broken-goal.goal.json")
        broken.writeText("{ malformed json")

        val snapshot = AgentStore(root).loadSnapshot()

        assertTrue(snapshot.goals.isEmpty())
        assertEquals(1, snapshot.quarantinedMissions.size)
        val entry = snapshot.quarantinedMissions.single()
        assertEquals(broken.name, entry.fileName)
        assertTrue(entry.reason.isNotBlank())
        assertTrue(entry.recoveryArtifactPath?.endsWith(".corrupt-recovery.txt") == true)
        assertTrue(entry.backupPresent.not())
    }

    @Test
    fun pendingDraftEncodingPreservesExactUserAuthoredRequest() {
        val root = Files.createTempDirectory("agent-store-draft").toFile()
        val store = AgentStore(root)
        val encode = AgentStore::class.java.getDeclaredMethod("encodeDraft", ResearchDraft::class.java).apply {
            isAccessible = true
        }
        val decode = AgentStore::class.java.getDeclaredMethod("decodeDraft", JSONObject::class.java).apply {
            isAccessible = true
        }
        val exact = "  What changed in the original filing, and who corrected it?  "
        val draft = ResearchDraft(
            conversationId = "conversation-1",
            originalUserRequest = exact,
            title = "Generated briefing title",
            question = "Model-normalized question",
            objective = "Investigate",
            status = ResearchDraftStatus.READY,
        )

        val encoded = encode.invoke(store, draft) as JSONObject
        val decoded = decode.invoke(store, encoded) as ResearchDraft
        assertEquals(exact, decoded.originalUserRequest)
        assertEquals("Model-normalized question", decoded.question)
    }

    private fun goal(id: String, request: String): AgentGoal = AgentGoal(
        id = id,
        conversationId = "conversation-$id",
        submissionId = "submission-$id",
        userRequest = request,
        title = "Mission $id",
        objective = "Investigate the request without losing provenance.",
        finalOutputDescription = "A source-linked report.",
        status = AgentGoalStatus.PLANNING,
        plannerModelId = "openrouter/auto-beta",
        executionModelId = "openrouter/auto-beta",
        tasks = emptyList(),
    )
}
