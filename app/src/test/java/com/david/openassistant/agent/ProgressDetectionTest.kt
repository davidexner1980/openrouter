package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.*

class ProgressDetectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: AgentStore
    private lateinit var executor: AgentTaskExecutor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val newUrl = request.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(request.newBuilder().url(newUrl).build())
            }
            .build()

        val diagnostics = RuntimeDiagnostics(null, tempFolder.newFolder("diag"), null)
        val client = AgentOpenRouterClient(
            toolRuntime = null,
            autonomyPolicy = AutonomyPolicy.DEFAULT,
            client = okHttpClient,
            researchMonitor = null,
            diagnostics = diagnostics,
            store = store,
            terminalHook = null,
            postActiveHook = null
        )
        
        executor = AgentTaskExecutor(
            client = client,
            store = store,
            diagnostics = diagnostics,
            autonomyPolicy = AutonomyPolicy.DEFAULT
        )
    }

    @Test
    fun testProgressNotCountedForRedundantFactsFromOtherTasks() = runBlocking {
        val goalId = "goal-1"
        val taskId1 = "task-1"
        val taskId2 = "task-2"
        val redundantFact = "This fact was already discovered by task 1."
        
        val task1 = AgentTask(id = taskId1, order = 0, title = "T1", instructions = "I", capability = AgentCapability.WEB_RESEARCH)
        val task2 = AgentTask(id = taskId2, order = 1, title = "T2", instructions = "I", capability = AgentCapability.SYNTHESIZE)
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "c1",
            userRequest = "r",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = listOf(task1, task2),
            claims = listOf(
                AgentClaim(
                    id = "claim-1",
                    taskId = taskId1,
                    text = redundantFact,
                    type = AgentClaimType.FACT,
                    confidence = 1.0,
                    support = AgentClaimSupport.SUPPORTED
                )
            ),
            acceptanceCriteria = listOf(AgentAcceptanceCriterion(id = "ac-1", description = "D"))
        )
        
        store.upsertGoal(goal)

        val acquisition = store.acquireTaskLeaseAtomic(goalId, "w1", taskId2)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // Model returns the SAME fact and fails acceptance check
        val successJson = """
            {
              "id": "gen-123",
              "choices": [
                {
                  "message": {
                    "content": "{\"work_product\": \"Result\", \"completion_score\": 0.5, \"claims\": [{\"id\": \"c2\", \"text\": \"$redundantFact\", \"type\": \"fact\", \"confidence\": 1.0}], \"acceptance_checks\": [{\"criterion_id\": \"ac-1\", \"status\": \"fail\"}], \"unresolved_questions\": []}"
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(successJson).build())

        val freshGoalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTaskSnapshot = freshGoalSnapshot.tasks.first { it.id == taskId2 }

        executor.executeOneTask("api-key", freshGoalSnapshot, freshTaskSnapshot, ticket)
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        
        // After fix: meaningful progress should be FALSE because the fact is redundant goal-wide.
        // Even if quality.passed is false, madeMeaningfulProgress should be false.
        
        assertEquals("noProgressCount should have incremented", 1, finalGoal.noProgressCount)
    }

    @Test
    fun testProgressNotCountedForRedundantSourcesFromOtherTasks() = runBlocking {
        val goalId = "goal-2"
        val taskId2 = "task-2"
        val redundantUrl = "https://example.com/redundant"
        
        val task2 = AgentTask(id = taskId2, order = 1, title = "T2", instructions = "I", capability = AgentCapability.WEB_RESEARCH)
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "c2",
            userRequest = "r",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = listOf(task2),
            sourceReads = listOf(
                SourceRead(
                    id = "read-1",
                    url = redundantUrl,
                    canonicalUrl = redundantUrl,
                    httpCode = 200,
                    contentType = "text/html",
                    content = "Content",
                    sourceRole = "discovery",
                    authorityScore = 10
                )
            ),
            acceptanceCriteria = listOf(AgentAcceptanceCriterion(id = "ac-1", description = "D"))
        )
        
        store.upsertGoal(goal)

        val acquisition = store.acquireTaskLeaseAtomic(goalId, "w1", taskId2)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // Model returns the SAME URL in sources and tool execution
        val successJson = """
            {
              "id": "gen-123",
              "choices": [
                {
                  "message": {
                    "content": "{\"work_product\": \"Result\", \"completion_score\": 0.5, \"claims\": [], \"acceptance_checks\": [], \"unresolved_questions\": []}",
                    "tool_calls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "public_web_fetch",
                          "arguments": "{\"url\": \"$redundantUrl\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(successJson).build())

        // And tool execution succeeds but returns what was already there
        // Actually, AgentTaskExecutor handles tool output and then calculates progress.
        
        val freshGoalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTaskSnapshot = freshGoalSnapshot.tasks.first { it.id == taskId2 }

        executor.executeOneTask("api-key", freshGoalSnapshot, freshTaskSnapshot, ticket)
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        
        // Meaningful progress should be false because the URL is redundant.
        assertEquals("noProgressCount should have incremented for redundant source", 1, finalGoal.noProgressCount)
    }
}
