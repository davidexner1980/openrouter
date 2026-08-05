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

class ProgressPrecisionIntegrationTest {

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
    fun testProgressDetectorUsesCanonicalUrlsForRedundancyAcrossTasks() = runBlocking {
        val goalId = "goal-1"
        val taskId1 = "task-1"
        val taskId2 = "task-2"
        val rawUrl = "https://example.com/"
        val alternateUrl = "https://example.com" // Different raw, same canonical
        
        val task1 = AgentTask(id = taskId1, order = 0, title = "T1", instructions = "I", capability = AgentCapability.WEB_RESEARCH)
        val task2 = AgentTask(id = taskId2, order = 1, title = "T2", instructions = "I", capability = AgentCapability.WEB_RESEARCH)
        
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
            sourceReads = listOf(
                SourceRead(
                    id = "read-1",
                    url = rawUrl,
                    canonicalUrl = ResearchQualityGate.canonicalSourceUrl(rawUrl),
                    httpCode = 200,
                    contentType = "text/html",
                    content = "Content",
                    sourceRole = "discovery",
                    authorityScore = 10
                )
            )
        )
        
        store.upsertGoal(goal)

        val acquisition = store.acquireTaskLeaseAtomic(goalId, "w1", taskId2)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

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
                          "arguments": "{\"url\": \"$alternateUrl\"}"
                        }
                      }
                    ]
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
        
        assertEquals("noProgressCount should have incremented for canonically redundant source from PRIOR task", 1, finalGoal.noProgressCount)
    }

    @Test
    fun testGoalTransitionsToResearchCyclesExhausted() = runBlocking {
        val goalId = "goal-2"
        val taskId = "task-1"
        
        // Task at attempt 5 (limit)
        val task = AgentTask(
            id = taskId,
            order = 0,
            title = "T",
            instructions = "I",
            capability = AgentCapability.REASON,
            attemptCount = 4 // Will become 5
        )
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
            tasks = listOf(task)
        )
        
        store.upsertGoal(goal)

        val acquisition = store.acquireTaskLeaseAtomic(goalId, "w1", taskId)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // Model returns failure (code 500)
        server.enqueue(MockResponse.Builder().code(500).body("Error").build())

        val freshGoalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTaskSnapshot = freshGoalSnapshot.tasks.first { it.id == taskId }

        executor.executeOneTask("api-key", freshGoalSnapshot, freshTaskSnapshot, ticket)
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Goal should transition to RESEARCH_CYCLES_EXHAUSTED", AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED, finalGoal.status)
    }
}
