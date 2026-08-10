package com.david.openassistant.agent

import com.david.openassistant.data.local.ConversationStore
import com.david.openassistant.data.local.StoredConversation
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeliveryCrashWindowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var agentStore: AgentStore
    private lateinit var conversationStore: ConversationStore
    private val goalId = "goal-1"
    private val convId = "conv-1"

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("stores")
        agentStore = AgentStore(baseDir = File(baseDir, "agent"))
        conversationStore = ConversationStore(baseDir = File(baseDir, "conv"))
    }

    @Test
    fun testTwoPhaseDeliveryProtocol() = runBlocking {
        val execGen = 2
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
            executionGeneration = execGen,
            result = "The Final Result"
        )
        agentStore.upsertGoal(goal, true)
        
        val conversation = StoredConversation.empty(id = convId)
        conversationStore.saveSnapshot(com.david.openassistant.data.local.ConversationSnapshot(listOf(conversation), convId))

        // Phase 1: Deliver. Should add message and marker.
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)

        val reloadedGoal = agentStore.loadSnapshot().goals.first { it.id == goalId }
        assertTrue(reloadedGoal.terminalResultDelivered)
        assertEquals(1, reloadedGoal.deliveryRecords.size)
        assertEquals(execGen, reloadedGoal.deliveryRecords[0].executionGeneration)

        val reloadedConv = conversationStore.loadSnapshot().conversations.first { it.id == convId }
        val deterministicId = "mission-delivery-$goalId-$execGen-TERMINAL_RESULT"
        assertTrue(reloadedConv.messages.any { it.id == deterministicId })
    }

    @Test
    fun testDeliveryIdempotency() = runBlocking {
        val execGen = 1
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
            executionGeneration = execGen,
            result = "The Final Result"
        )
        agentStore.upsertGoal(goal, true)
        
        val conversation = StoredConversation.empty(id = convId)
        conversationStore.saveSnapshot(com.david.openassistant.data.local.ConversationSnapshot(listOf(conversation), convId))

        // Deliver twice
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)
        AgentResultDeliveryLogic.deliverTerminalResultIfPending(goalId, agentStore, conversationStore)

        val reloadedConv = conversationStore.loadSnapshot().conversations.first { it.id == convId }
        val deterministicId = "mission-delivery-$goalId-$execGen-TERMINAL_RESULT"
        assertEquals(1, reloadedConv.messages.count { it.id == deterministicId })
    }
}
