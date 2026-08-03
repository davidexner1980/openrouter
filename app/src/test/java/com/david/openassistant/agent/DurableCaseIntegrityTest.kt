package com.david.openassistant.agent

import android.content.SharedPreferences
import com.david.openassistant.domain.AgentInteractor
import com.david.openassistant.domain.MissionStartResult
import com.david.openassistant.data.diagnostics.ResearchMonitor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy

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

    private fun createFakePrefs(): SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> map[args[0] as String] ?: args[1]
                "getBoolean" -> map[args[0] as String] ?: args[1]
                "getLong" -> map[args[0] as String] ?: args[1]
                "getInt" -> map[args[0] as String] ?: args[1]
                "edit" -> createFakeEditor(map)
                "registerOnSharedPreferenceChangeListener" -> Unit
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> null
            }
        } as SharedPreferences
    }

    private fun createFakeEditor(map: MutableMap<String, Any?>): SharedPreferences.Editor {
        val tempMap = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { tempMap[args[0] as String] = args[1]; null }
                "putBoolean" -> { tempMap[args[0] as String] = args[1]; null }
                "putLong" -> { tempMap[args[0] as String] = args[1]; null }
                "putInt" -> { tempMap[args[0] as String] = args[1]; null }
                "remove" -> { tempMap.remove(args[0] as String); null }
                "clear" -> { tempMap.clear(); null }
                "commit", "apply" -> { map.putAll(tempMap); true }
                else -> null
            }
        } as SharedPreferences.Editor
    }

    @Test
    fun blankOriginalRequestIsRejectedByMissionCreationOwner() = runBlocking {
        val root = Files.createTempDirectory("agent-interactor-empty-request").toFile()
        val store = AgentStore(root)
        val interactor = AgentInteractor(
            context = null,
            agentStore = store,
            agentScheduler = null,
            boundaryHook = object : MissionStartBoundaryHook {
                override suspend fun onBoundaryReached(boundary: String, draft: ResearchDraft) {}
            }
        )
        
        val invalidDraft = ResearchDraft(
            id = "d1",
            conversationId = "c1",
            originalUserRequest = "", // BLANK
            title = "Title",
            objective = "Objective",
            status = ResearchDraftStatus.READY
        )

        val result = interactor.startMissionFromBrief(
            draft = invalidDraft,
            monitor = ResearchMonitor(createFakePrefs(), File(root, "monitor"), File(root, "cache")),
            hasCredential = true,
            keyInfo = null,
            models = emptyList(),
            selectedModelId = null,
            routingProfileName = "AUTO",
            automaticStart = true
        )

        assertTrue("Expected InvalidMissionData, got $result", result is MissionStartResult.InvalidMissionData)
        assertEquals("The exact original user request is missing.", (result as MissionStartResult.InvalidMissionData).reason)
        assertTrue("Store should be empty", store.loadSnapshot().goals.isEmpty())
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
        assertEquals("Decoded goal status should be CORRUPT_OR_INCOMPLETE_MISSION", AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, decoded.status)
        assertTrue("Decoded goal should be marked as corrupt", decoded.isCorrupt)
        assertTrue("Decoded goal error should contain 'original user request'. Got: ${decoded.error}", decoded.error.orEmpty().contains("original user request", ignoreCase = true))
        assertFalse("Title should not use placeholder", decoded.title == "Automated goal")
        assertFalse("User request should not use placeholder", decoded.userRequest == "No conversation provided.")
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
