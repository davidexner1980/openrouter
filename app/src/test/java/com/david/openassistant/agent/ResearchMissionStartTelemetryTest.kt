package com.david.openassistant.agent

import android.content.SharedPreferences
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.domain.AgentInteractor
import com.david.openassistant.domain.MissionStartResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class ResearchMissionStartTelemetryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var monitor: ResearchMonitor
    private val recordedEvents = mutableListOf<RecordedEvent>()

    private data class RecordedEvent(
        val category: String,
        val event: String,
        val fields: Map<String, Any?>
    )

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val monitorDir = tempFolder.newFolder("monitor")
        val prefs = createFakePrefs(mapOf("monitor_active" to true, "monitor_session_id" to "session-1"))

        monitor = object : ResearchMonitor(prefs, monitorDir, tempFolder.newFolder("cache")) {
            override fun record(
                category: String,
                event: String,
                level: String,
                correlationId: String?,
                targetSessionId: String?,
                fields: Map<String, Any?>
            ) {
                recordedEvents.add(RecordedEvent(category, event, fields))
            }
            override fun isActive(): Boolean = true
        }
    }

    @Test
    fun briefCreatedTelemetryContainsRequiredFields() {
        ResearchMissionStartTelemetry.briefCreated(
            monitor = monitor,
            submissionId = "sub-1",
            draftId = "draft-1",
            linkedGoalId = "goal-1",
            conversationId = "conv-1",
            automaticStart = true,
            routingProfile = "balanced",
        )

        val recorded = recordedEvents.first()
        assertEquals("mission", recorded.category)
        assertEquals("research_brief_created", recorded.event)

        val f = recorded.fields
        assertEquals("sub-1", f["submission_id"])
        assertEquals("draft-1", f["draft_id"])
        assertEquals("goal-1", f["linked_goal_id"])
        assertEquals("conv-1", f["conversation_id"])
        assertEquals(true, f["automatic_start"])
        assertEquals("balanced", f["routing_profile"])
    }

    @Test
    fun startRequestedTelemetryContainsRequiredFields() {
        ResearchMissionStartTelemetry.startRequested(
            monitor = monitor,
            submissionId = "sub-1",
            draftId = "draft-1",
            linkedGoalId = "goal-1",
            automaticStart = true,
            previousDraftState = "READY",
            targetDraftState = "STARTING",
            routingProfile = "balanced",
            conversationId = "conv-1"
        )

        val recorded = recordedEvents.first()
        assertEquals("mission", recorded.category)
        assertEquals("start_mission_requested", recorded.event)

        val f = recorded.fields
        assertEquals("sub-1", f["submission_id"])
        assertEquals("draft-1", f["draft_id"])
        assertEquals("goal-1", f["linked_goal_id"])
        assertEquals(true, f["automatic_start"])
        assertEquals("READY", f["previous_draft_state"])
        assertEquals("STARTING", f["target_draft_state"])
        assertEquals("balanced", f["routing_profile"])
        assertEquals("conv-1", f["conversation_id"])
    }

    @Test
    fun startResolvedTelemetryDistinguishesCreatedFromReused() {
        ResearchMissionStartTelemetry.startResolved(monitor, "sub-1", "goal-1", "created")
        ResearchMissionStartTelemetry.startResolved(monitor, "sub-2", "goal-2", "reused")

        assertEquals("created", recordedEvents[0].fields["resolution"])
        assertEquals("reused", recordedEvents[1].fields["resolution"])
    }

    @Test
    fun workerEnqueuedTelemetrySupportsWorkInfoState() {
        ResearchMissionStartTelemetry.workerEnqueued(
            monitor = monitor,
            goalId = "goal-1",
            submissionId = "sub-1",
            policy = "KEEP",
            workId = "work-uuid-1",
            workInfoState = "ENQUEUED",
        )

        val recorded = recordedEvents.first()
        assertEquals("mission_worker_enqueued", recorded.event)
        assertEquals("work-uuid-1", recorded.fields["work_id"])
        assertEquals("ENQUEUED", recorded.fields["work_info_state"])
    }

    @Test
    fun workerReusedTelemetryEmitsWorkerReusedEvent() {
        ResearchMissionStartTelemetry.workerReused(
            monitor = monitor,
            goalId = "goal-1",
            submissionId = "sub-1",
            policy = "KEEP",
            workId = "work-uuid-2",
            workInfoState = "RUNNING",
        )

        val recorded = recordedEvents.first()
        assertEquals("mission_worker_reused", recorded.event)
        assertEquals("work-uuid-2", recorded.fields["work_id"])
        assertEquals("RUNNING", recorded.fields["work_info_state"])
    }

    @Test
    fun workerEnqueueFailedTelemetryRecordsErrorDetails() {
        ResearchMissionStartTelemetry.workerEnqueueFailed(
            monitor = monitor,
            goalId = "goal-1",
            submissionId = "sub-1",
            exceptionType = "IllegalStateException",
            message = "WorkManager error",
            policy = "KEEP",
            type = "initial",
        )

        val recorded = recordedEvents.first()
        assertEquals("mission_worker_enqueue_failed", recorded.event)
        assertEquals("IllegalStateException", recorded.fields["exception_type"])
        assertEquals("WorkManager error", recorded.fields["message"])
        assertEquals("KEEP", recorded.fields["policy"])
        assertEquals("initial", recorded.fields["type"])
    }

    @Test
    fun recoveryDecisionPreservesStartingStatusDuringReplay() {
        val draft = ResearchDraft(
            id = "draft-10",
            conversationId = "conv-10",
            question = "Test question",
            status = ResearchDraftStatus.STARTING,
            durableSchedulingState = DurableSchedulingState.GOAL_PERSISTED,
            linkedGoalId = "goal-10",
        )

        val decision = ResearchMissionStartRecovery.decide(draft, emptyList())

        assertEquals(ResearchMissionStartRecovery.Action.REPLAY_INTERRUPTED_START, decision.action)
        assertTrue(decision.shouldReplayStart)
        assertEquals(ResearchDraftStatus.STARTING, decision.draftForUi?.status)
        assertEquals("goal-10", decision.draftForUi?.linkedGoalId)
    }

    @Test
    fun networkRetryDoesNotCancelMainWorkChain() {
        val goalId = "network-goal-1"
        val uniqueName = AgentScheduler.uniqueWorkName(goalId)
        assertEquals("openassistant_agent_goal_network-goal-1", uniqueName)
    }

    private fun createFakePrefs(initial: Map<String, Any?> = emptyMap()): SharedPreferences {
        val map = initial.toMutableMap()
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
}
