package com.david.openassistant.data.openrouter

import com.david.openassistant.domain.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ToolLoopLivenessTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun toolLoopTerminatesAfterMaxRounds() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.toString().contains("openrouter.ai")) {
                    val newUrl = request.url.newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                    chain.proceed(request.newBuilder().url(newUrl).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val customClient = OpenRouterClient(client = okHttpClient)

        // Enqueue enough responses to hit the limit
        repeat(OpenRouterClient.MAX_TOOL_ROUNDS + 1) { i ->
            val toolCallJson = JSONObject()
                .put("id", "call-$i")
                .put("type", "function")
                .put("function", JSONObject().put("name", "test_tool").put("arguments", "{\"i\":$i}"))
            
            val toolChoiceResponse = JSONObject()
                .put("id", "res-$i")
                .put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(toolCallJson)))
                ))
                .toString()
                
            server.enqueue(MockResponse.Builder().code(200).body(toolChoiceResponse).build())
        }

        val exception = assertThrows(OpenRouterException::class.java) {
            runBlocking {
                customClient.runAutomaticToolLoop(
                    apiKey = "sk-or-test",
                    modelId = "openrouter/auto-beta",
                    messages = emptyList(),
                    toolDefinitions = { JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "test_tool"))) },
                    executeTool = {
                        ToolExecutionResult("ok", "ok")
                    }
                )
            }
        }

        assertTrue("Expected round limit error, got: ${exception.userMessage}", 
            exception.userMessage.contains("exceeded the maximum of ${OpenRouterClient.MAX_TOOL_ROUNDS} rounds"))
        assertEquals(OpenRouterClient.MAX_TOOL_ROUNDS, server.requestCount)
    }

    @Test
    fun toolLoopTerminatesAfterMaxExecutions() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.toString().contains("openrouter.ai")) {
                    val newUrl = request.url.newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                    chain.proceed(request.newBuilder().url(newUrl).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val customClient = OpenRouterClient(client = okHttpClient)

        // Mock a response with MANY tool calls in one round
        val toolCalls = JSONArray()
        repeat(OpenRouterClient.MAX_TOOL_EXECUTIONS + 1) { i ->
            toolCalls.put(JSONObject()
                .put("id", "call-$i")
                .put("type", "function")
                .put("function", JSONObject().put("name", "test_tool").put("arguments", "{\"i\":$i}"))
            )
        }
        
        val toolChoiceResponse = JSONObject()
            .put("id", "res-1")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("tool_calls", toolCalls))
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(toolChoiceResponse).build())

        val exception = assertThrows(OpenRouterException::class.java) {
            runBlocking {
                customClient.runAutomaticToolLoop(
                    apiKey = "sk-or-test",
                    modelId = "openrouter/auto-beta",
                    messages = emptyList(),
                    toolDefinitions = { JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "test_tool"))) },
                    executeTool = { ToolExecutionResult("ok", "ok") }
                )
            }
        }

        assertTrue("Expected execution limit error, got: ${exception.userMessage}", 
            exception.userMessage.contains("exceeded the maximum of ${OpenRouterClient.MAX_TOOL_EXECUTIONS} tool calls"))
    }

    @Test
    fun toolLoopTerminatesOnRepeatedIdenticalCallsWithWhitespaceVariation() = runBlocking {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.toString().contains("openrouter.ai")) {
                    val newUrl = request.url.newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                    chain.proceed(request.newBuilder().url(newUrl).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val customClient = OpenRouterClient(client = okHttpClient)

        // Enqueue 3 responses with identical but slightly different formatted arguments
        val variant1 = JSONObject().put("id", "res-1").put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
            JSONObject().put("type", "function").put("function", JSONObject().put("name", "test").put("arguments", "{\"q\":\"test\"}"))
        ))))).toString()
        
        val variant2 = JSONObject().put("id", "res-2").put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
            JSONObject().put("type", "function").put("function", JSONObject().put("name", "test").put("arguments", " {  \"q\" :  \"test\" } "))
        ))))).toString()
        
        val variant3 = JSONObject().put("id", "res-3").put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(
            JSONObject().put("type", "function").put("function", JSONObject().put("name", "test").put("arguments", "{\"q\":\"test\"} "))
        ))))).toString()

        server.enqueue(MockResponse.Builder().code(200).body(variant1).build())
        server.enqueue(MockResponse.Builder().code(200).body(variant2).build())
        server.enqueue(MockResponse.Builder().code(200).body(variant3).build())

        val exception = assertThrows(OpenRouterException::class.java) {
            runBlocking {
                customClient.runAutomaticToolLoop(
                    apiKey = "sk-or-test",
                    modelId = "openrouter/auto-beta",
                    messages = emptyList(),
                    toolDefinitions = { JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "test"))) },
                    executeTool = { ToolExecutionResult("ok", "ok") }
                )
            }
        }

        assertTrue("Expected no-progress error, got: ${exception.userMessage}", 
            exception.userMessage.contains("repeated the same tool requests without making progress"))
    }
}
