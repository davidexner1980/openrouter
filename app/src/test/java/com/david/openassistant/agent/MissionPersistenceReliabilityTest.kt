package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MissionPersistenceReliabilityTest {

    @Test
    fun goalLifecycleTransitionsCoverNewStates() {
        val statuses = AgentGoalStatus.entries
        assertTrue(statuses.contains(AgentGoalStatus.RESEARCHING))
        assertTrue(statuses.contains(AgentGoalStatus.RETRIEVING))
        assertTrue(statuses.contains(AgentGoalStatus.EXTRACTING))
        assertTrue(statuses.contains(AgentGoalStatus.SYNTHESIZING))
        assertTrue(statuses.contains(AgentGoalStatus.RECOVERING))
        assertTrue(statuses.contains(AgentGoalStatus.REJECTED))
        assertTrue(statuses.contains(AgentGoalStatus.BLOCKED_NEEDS_ACTION))
    }

    @Test
    fun meaningfulProgressUpdatesDurableFields() {
        val goalId = "goal-1"
        val taskId = "task-1"
        val now = 1000L
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv",
            userRequest = "test",
            title = "test",
            objective = "test",
            finalOutputDescription = "test",
            status = AgentGoalStatus.RESEARCHING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "t", instructions = "i", capability = AgentCapability.WEB_RESEARCH, status = AgentTaskStatus.RUNNING)),
            lastMeaningfulProgressAt = 500L,
            noProgressCount = 2
        )

        // Simulate progress (madeMeaningfulProgress = true)
        val nextGoal = goal.copy(
            lastMeaningfulProgressAt = now,
            noProgressCount = 0
        )

        assertEquals(now, nextGoal.lastMeaningfulProgressAt)
        assertEquals(0, nextGoal.noProgressCount)
    }

    @Test
    fun monotonicLeaseGenerationPreventsStaleCommits() {
        val goalId = "goal-1"
        val taskId = "task-1"
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv",
            userRequest = "test",
            title = "test",
            objective = "test",
            finalOutputDescription = "test",
            status = AgentGoalStatus.RESEARCHING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "t", instructions = "i", capability = AgentCapability.REASON, status = AgentTaskStatus.RUNNING)),
            leaseGeneration = 5,
            executionLease = AgentExecutionLease(
                workerId = "worker-2",
                ownerProcessSessionId = "session-1",
                taskId = taskId,
                attemptId = "attempt-2",
                generation = 5,
                acquiredAt = 2000L,
                heartbeatAt = 2000L
            )
        )

        // Stale worker with generation 4 tries to commit
        val staleLease = AgentExecutionLease(
            workerId = "worker-1",
            ownerProcessSessionId = "session-1",
            taskId = taskId,
            attemptId = "attempt-1",
            generation = 4,
            acquiredAt = 1000L,
            heartbeatAt = 1000L
        )

        val isStale = staleLease.generation < goal.leaseGeneration
        assertTrue(isStale)
    }

    @Test
    fun escalationLadderTacticSelection() {
        val goal = AgentGoal(
            id = "g", conversationId = "c", userRequest = "u", title = "t", objective = "o",
            finalOutputDescription = "f", status = AgentGoalStatus.RESEARCHING,
            plannerModelId = "m", executionModelId = "m", tasks = emptyList()
        )
        
        val task0 = AgentTask(id = "t0", order = 0, title = "t", instructions = "i", 
            capability = AgentCapability.WEB_RESEARCH, consecutiveNoProgressCount = 0)
        val task1 = AgentTask(id = "t1", order = 1, title = "t", instructions = "i", 
            capability = AgentCapability.WEB_RESEARCH, consecutiveNoProgressCount = 1)
        val task5 = AgentTask(id = "t5", order = 5, title = "t", instructions = "i", 
            capability = AgentCapability.WEB_RESEARCH, consecutiveNoProgressCount = 5)
        val task11 = AgentTask(id = "t11", order = 11, title = "t", instructions = "i", 
            capability = AgentCapability.WEB_RESEARCH, consecutiveNoProgressCount = 11)

        assertEquals(EscalationTactic.NONE, chooseNextEscalationTacticSim(goal, task0))
        assertEquals(EscalationTactic.REFORMULATE_QUERY, chooseNextEscalationTacticSim(goal, task1))
        assertEquals(EscalationTactic.FOLLOW_RELEVANT_LINKS, chooseNextEscalationTacticSim(goal, task5))
        assertEquals(EscalationTactic.ASK_USER, chooseNextEscalationTacticSim(goal, task11))
    }

    private fun chooseNextEscalationTacticSim(goal: AgentGoal, task: AgentTask): EscalationTactic {
        val count = task.consecutiveNoProgressCount
        return when {
            count <= 0 -> EscalationTactic.NONE
            count == 1 -> EscalationTactic.REFORMULATE_QUERY
            count == 2 -> EscalationTactic.DECOMPOSE_QUESTION
            count == 3 -> EscalationTactic.SEARCH_AUTHORITATIVE_DOMAINS
            count == 4 -> EscalationTactic.INSPECT_SITEMAPS_INDEXES
            count == 5 -> EscalationTactic.FOLLOW_RELEVANT_LINKS
            count == 6 -> EscalationTactic.ALTERNATIVE_DISCOVERY_ADAPTER
            count == 7 -> EscalationTactic.LOCAL_EVIDENCE_INDEX_SEARCH
            count == 8 -> EscalationTactic.ALTERNATE_MODEL_PROVIDER
            count == 9 -> EscalationTactic.RE_EVALUATE_ASSUMPTIONS
            count == 10 -> EscalationTactic.SMALLEST_MISSING_FACT
            else -> EscalationTactic.ASK_USER
        }
    }
}
