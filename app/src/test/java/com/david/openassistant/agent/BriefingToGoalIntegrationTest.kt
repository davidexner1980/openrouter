package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BriefingToGoalIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun multiTurnMessagesToDraftAndGoalPreservesResolvedRequestInStore() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        val store = AgentStore(baseDir)

        val conversationId = "conv-integration-1"
        val messages = listOf(
            ChatMessage(id = "msg-1", role = ChatRole.USER, content = "I'm looking to buy the best take down recurve bow over $500"),
            ChatMessage(id = "msg-2", role = ChatRole.USER, content = "more Hunting/3D"),
            ChatMessage(id = "msg-3", role = ChatRole.USER, content = "needs to have Modern ILF"),
        )

        val resolved = ResolvedResearchRequest.resolveFromHistory(messages, conversationId)

        val draft = ResearchDraft(
            id = "draft-1",
            conversationId = conversationId,
            originalUserRequest = resolved.resolvedRequest,
            resolvedResearchRequest = resolved,
            title = "Best Modern ILF Takedown Recurve Bow",
            question = "Which modern ILF takedown recurve bow over $500 is best for hunting and 3D archery?",
            objective = "Evaluate top ILF takedown recurve bows over $500 for hunting/3D.",
            sourceMessageIds = messages.map { it.id },
            status = ResearchDraftStatus.READY,
        )

        val goal = AgentGoal(
            id = "goal-1",
            conversationId = draft.conversationId,
            submissionId = draft.id,
            userRequest = draft.originalUserRequest,
            resolvedResearchRequest = draft.resolvedResearchRequest,
            title = draft.title,
            objective = draft.objective,
            finalOutputDescription = draft.desiredDeliverable,
            status = AgentGoalStatus.PLANNING,
            plannerModelId = "openrouter/auto",
            executionModelId = "openrouter/auto",
            tasks = emptyList(),
        )

        store.upsertGoal(goal, select = true)

        val snapshot = store.loadSnapshot()
        val reloadedGoal = snapshot.goals.firstOrNull { it.id == "goal-1" }

        assertNotNull(reloadedGoal)
        val loadedResolved = reloadedGoal?.resolvedResearchRequest
        assertNotNull(loadedResolved)
        assertEquals("I'm looking to buy the best take down recurve bow over $500", loadedResolved?.originalBaseRequest)
        assertEquals("needs to have Modern ILF", loadedResolved?.latestLiteralUserMessage)
        assertEquals(3, loadedResolved?.sourceMessageIds?.size)
        assertTrue(loadedResolved?.resolvedRequest?.contains("Modern ILF") == true)
        assertTrue(loadedResolved?.resolvedRequest?.contains("Hunting/3D") == true)
        assertTrue(loadedResolved?.resolvedRequest?.contains("500") == true)
    }
}
