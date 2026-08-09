package com.david.openassistant.agent

import android.content.SharedPreferences
import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.local.ConversationStore
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.data.openrouter.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class AgentResultDeliveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var conversationStore: ConversationStore

    @Before
    fun setUp() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)
        
        val convDir = tempFolder.newFolder("conversations")
        val prefs = createFakePrefs()
        
        conversationStore = ConversationStore(baseDir = convDir, prefs = prefs)
    }

    @Test
    fun testTerminalResultDelivery() {
        val goalId = UUID.randomUUID().toString()
        val conversationId = "conv-1"
        
        val conversation = StoredConversation.empty(id = conversationId)
        conversationStore.saveSnapshot(ConversationSnapshot(listOf(conversation), conversationId))
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = conversationId,
            userRequest = "R",
            title = "Mission Title",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
            plannerModelId = "m1",
            executionModelId = "m2",
            tasks = emptyList(),
            result = "The final findings are here.",
            terminalResultDelivered = false
        )
        store.saveSnapshot(AgentSnapshot(goals = listOf(goal)))
        
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, store, conversationStore, null)
        
        // 1. Verify message in conversation
        val convSnapshot = conversationStore.loadSnapshot()
        val updatedConv = convSnapshot.conversations.first { it.id == conversationId }
        assertEquals(1, updatedConv.messages.size)
        val message = updatedConv.messages.first()
        assertEquals(ChatRole.ASSISTANT, message.role)
        assertTrue(message.content.contains("The final findings are here."))
        assertTrue(message.content.contains("mission://$goalId"))
        
        // 2. Verify flag updated in store
        val goalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        assertTrue(goalSnapshot.terminalResultDelivered)
        
        // 3. Verify exactly-once (second call does nothing)
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, store, conversationStore, null)
        val convSnapshot2 = conversationStore.loadSnapshot()
        val updatedConv2 = convSnapshot2.conversations.first { it.id == conversationId }
        assertEquals("Should not deliver twice", 1, updatedConv2.messages.size)
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
        return Proxy.newProxyInstance(
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
                "clear" -> {
                    tempMap.clear()
                    proxy
                }
                "commit", "apply" -> {
                    map.putAll(tempMap)
                    true
                }
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            handler
        ) as SharedPreferences.Editor
    }
}
