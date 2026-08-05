package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.*

class EvidenceContextSelectorTest {

    @Test
    fun testSelectorPrioritizesLatestEvidenceWhenWindowIsFull() {
        val taskId = "task-1"
        val task = AgentTask(
            id = taskId,
            order = 0,
            title = "Current Task",
            instructions = "I",
            capability = AgentCapability.REASON
        )

        val manyEvidence = (1..10).map { i ->
            AgentEvidence(
                id = "old-$i",
                taskId = "other",
                kind = AgentEvidenceKind.WEB_RESEARCH,
                title = "Current Task Evidence $i", // High overlap with task title "Current Task"
                summary = "S",
                content = "Content $i ".repeat(500), // ~5000 chars
                createdAt = i.toLong()
            )
        }
        
        val latestEvidence = AgentEvidence(
            id = "latest",
            taskId = "previous-task",
            kind = AgentEvidenceKind.WEB_RESEARCH,
            title = "Latest Evidence",
            summary = "S",
            content = "Substantive content here ".repeat(200), // ~5000 chars
            createdAt = 100L
        )
        
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "c1",
            userRequest = "r",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = listOf(task),
            evidence = manyEvidence + latestEvidence
        )

        // Set character limit so only a few items fit
        val result = EvidenceContextSelector.select(goal, task, maxItems = 20, maxCharacters = 15_000)
        
        // Before fix: 'latest' is added last. 
        // 3 old items (~5800 chars each with overhead) would fill 15k.
        // If 'latest' is added after 3 items, it won't fit.
        
        assertTrue("Latest evidence should be present in context", 
            result.evidence.any { it.id == "latest" })
    }
}
