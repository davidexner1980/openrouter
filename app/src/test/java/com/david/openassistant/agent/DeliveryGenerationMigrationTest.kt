package com.david.openassistant.agent

import android.content.SharedPreferences
import com.david.openassistant.data.local.ConversationStore
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.domain.model.ModelProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.InvocationHandler
import java.util.*

class DeliveryGenerationMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var agentStore: AgentStore
    private lateinit var conversationStore: ConversationStore
    private lateinit var goalId: String
    private lateinit var convId: String

    @Before
    fun setup() {
        val agentDir = tempFolder.newFolder("agent_store")
        agentStore = AgentStore(baseDir = agentDir)
        
        val convDir = tempFolder.newFolder("conv_store")
        val prefs = createFakePrefs()
        conversationStore = ConversationStore(baseDir = convDir, prefs = prefs)
        
        goalId = "goal-1"
        convId = "conv-1"
        
        val emptyConv = StoredConversation(
            id = convId,
            title = "Test",
            updatedAt = System.currentTimeMillis(),
            selectedModelId = "model",
            modelProfile = ModelProfile.MANUAL,
            messages = emptyList()
        )
        conversationStore.saveSnapshot(com.david.openassistant.data.local.ConversationSnapshot(listOf(emptyConv), convId))
    }

    private fun createFakePrefs(): SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getString" -> map[args!![0] as String] ?: args[1]
                "getBoolean" -> map[args!![0] as String] ?: args[1]
                "getLong" -> map[args!![0] as String] ?: args[1]
                "getInt" -> map[args!![0] as String] ?: args[1]
                "edit" -> createFakeEditor(map)
                "registerOnSharedPreferenceChangeListener" -> Unit
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            handler
        ) as SharedPreferences
    }

    private fun createFakeEditor(map: MutableMap<String, Any?>): SharedPreferences.Editor {
        val tempMap = mutableMapOf<String, Any?>()
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "putString", "putBoolean", "putLong", "putInt", "remove" -> {
                    tempMap[args!![0] as String] = args[1]
                    proxy
                }
                "apply", "commit" -> {
                    map.putAll(tempMap)
                    if (method.name == "commit") true else Unit
                }
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            handler
        ) as SharedPreferences.Editor
    }

    @Test
    fun testExactlyOnceDeliveryWithExecutionGeneration() = runBlocking {
        val goal = AgentGoal(
            id = goalId,
            conversationId = convId,
            userRequest = "Req",
            title = "Title",
            objective = "Obj",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.COMPLETED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList(),
            leaseGeneration = 5,
            executionGeneration = 1
        )
        agentStore.upsertGoal(goal, true)

        // First delivery
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)
        
        Thread.sleep(200) // Stability for Windows file system rename
        
        val convAfter1 = conversationStore.loadSnapshot().activeConversation
        assertEquals(1, convAfter1.messages.size)
        val message1Id = convAfter1.messages.first().id
        assertTrue(message1Id.startsWith("mission-delivery-$goalId-1-"))

        val goalAfter1 = agentStore.loadSnapshot().goals.first { it.id == goalId }
        assertTrue(goalAfter1.deliveryRecords.any { it.executionGeneration == 1 })

        // Second delivery (should be no-op)
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)
        
        val convAfter2 = conversationStore.loadSnapshot().activeConversation
        assertEquals(1, convAfter2.messages.size)
        assertEquals(message1Id, convAfter2.messages.first().id)
    }

    @Test
    fun testRestartIncrementsExecutionGenerationAndDeliversAgain() = runBlocking {
        val goal = AgentGoal(
            id = goalId,
            conversationId = convId,
            userRequest = "Req",
            title = "Title",
            objective = "Obj",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.CANCELLED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList(),
            leaseGeneration = 1,
            executionGeneration = 1
        )
        agentStore.upsertGoal(goal, true)

        // Deliver cancellation result for Gen 1
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)
        Thread.sleep(200)
        assertEquals(1, conversationStore.loadSnapshot().activeConversation.messages.size)

        // Restart
        agentStore.updateGoal(goalId) { current ->
            AgentLifecycleReducer.restart(current)
        }
        
        val restarted = agentStore.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(2, restarted.executionGeneration)
        assertEquals(AgentGoalStatus.PLANNING, restarted.status)

        // Complete the restarted mission
        agentStore.updateGoal(goalId) { it.copy(status = AgentGoalStatus.COMPLETED) }
        
        // Deliver Gen 2 result
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)
        Thread.sleep(200)
        
        val convFinal = conversationStore.loadSnapshot().activeConversation
        assertEquals(2, convFinal.messages.size)
        assertTrue(convFinal.messages.any { it.id.contains("-1-") })
        assertTrue(convFinal.messages.any { it.id.contains("-2-") })
    }

    @Test
    fun testLegacyDeliveryDoesNotSuppressNewGeneration() = runBlocking {
        val goal = AgentGoal(
            id = goalId,
            conversationId = convId,
            userRequest = "Req",
            title = "Title",
            objective = "Obj",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.COMPLETED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = emptyList(),
            leaseGeneration = 5,
            executionGeneration = 2,
            terminalResultDelivered = true,
            deliveryRecords = listOf(DeliveryRecord(generation = 4, deliveryKind = "TERMINAL_RESULT", isLegacy = true))
        )
        agentStore.upsertGoal(goal, true)

        // Gen 2 result should still deliver because current records are legacy/different generation
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)
        Thread.sleep(200)
        
        val conv = conversationStore.loadSnapshot().activeConversation
        assertEquals(1, conv.messages.size)
        assertTrue(conv.messages.first().id.contains("-2-"))
    }
}
