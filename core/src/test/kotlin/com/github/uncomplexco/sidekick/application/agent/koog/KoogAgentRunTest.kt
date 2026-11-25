package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.testing.QueuedOpenRouterServer
import com.github.uncomplexco.sidekick.testing.QueuedOpenRouterServer.Response
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class KoogAgentRunTest {
    @Test
    fun `native output schema is sent only after tool execution on the OpenRouter wire`() =
        runBlocking {
            val schema =
                Json.parseToJsonElement(
                        """{"type":"object","properties":{"evidence":{"type":"string"}},"required":["evidence"],"additionalProperties":false}"""
                    )
                    .jsonObject
            val output = """{"evidence":"Inspected"}"""
            QueuedOpenRouterServer(
                    Response(
                        """{"id":"test","object":"chat.completion","created":1794094917,"model":"z-ai/glm-5.3","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"inspect","arguments":"{}"}}]}}]}"""
                    ),
                    Response(
                        """{"id":"test","object":"chat.completion","created":1794094917,"model":"z-ai/glm-5.3","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":"I'll inspect the source next.","tool_calls":[{"id":"call-2","type":"function","function":{"name":"inspect","arguments":"{}"}}]}}]}"""
                    ),
                    Response(
                        """{"id":"test","object":"chat.completion","created":1794094917,"model":"z-ai/glm-5.3","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Inspection complete."}}]}"""
                    ),
                    Response(
                        """{"id":"test","object":"chat.completion","created":1794094917,"model":"z-ai/glm-5.3","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":${JsonPrimitive(output)}}}]}"""
                    ),
                )
                .use { server ->
                    val client =
                        OpenRouterLLMClient(
                            apiKey = "test-key",
                            settings = OpenRouterClientSettings(baseUrl = server.baseUrl),
                            httpClientFactory = HttpClientFactoryResolver.resolve(),
                        )
                    val executor = MultiLLMPromptExecutor(LLMProvider.OpenRouter to client)

                    val result =
                        runAgent(executor, ToolRegistry { tool(UsageCountingTool()) }, schema)

                    assertEquals(output, assertIs<AgentRunResult.Completed<String>>(result).output)
                    val requests = server.requests.map { Json.parseToJsonElement(it).jsonObject }
                    assertEquals(4, requests.size)
                    val expectedFormat =
                        Json.parseToJsonElement(
                            """{"type":"json_schema","json_schema":{"name":"task_result","strict":true,"schema":$schema}}"""
                        )
                    for (request in requests.dropLast(1)) {
                        assertEquals(null, request["response_format"])
                        assertTrue(request.getValue("tools").jsonArray.isNotEmpty())
                    }
                    assertTrue(
                        requests.first().getValue("messages").jsonArray.any {
                            it.jsonObject["content"].toString().contains("evidence")
                        }
                    )
                    assertEquals(expectedFormat, requests.last()["response_format"])
                    assertTrue(requests.last()["tools"]?.jsonArray.isNullOrEmpty())
                    assertEquals(
                        JsonPrimitive(true),
                        requests.last()["provider"]?.jsonObject?.get("require_parameters"),
                    )
                    val evidence =
                        requests
                            .get(2)
                            .getValue("messages")
                            .jsonArray
                            .map { it.jsonObject }
                            .filter { it["role"] == JsonPrimitive("tool") }
                    assertEquals(
                        listOf("call-1", "call-2").map(::JsonPrimitive),
                        evidence.map { it["tool_call_id"] },
                    )
                    assertEquals(
                        List(2) { JsonPrimitive("Inspected") },
                        evidence.map { it["content"] },
                    )
                    val finalHistory = requests.last().getValue("messages").jsonArray
                    assertTrue(
                        finalHistory.any {
                            it.jsonObject["content"].toString().contains("Inspected")
                        }
                    )
                    assertTrue(
                        finalHistory.any {
                            it.jsonObject["content"] == JsonPrimitive("Inspection complete.")
                        }
                    )
                }
        }

    @Test
    fun `completed run sums model usage and tool attempts`() = runBlocking {
        val tool = UsageCountingTool()
        val executor = spy(QueuedPromptExecutor(toolRequest(), finalResponse()))

        val result = runAgent(executor, ToolRegistry { tool(tool) })

        val completed = assertIs<AgentRunResult.Completed<String>>(result)
        assertEquals("Finished", completed.output)
        assertEquals(AgentUsageStats(1, 30, 7), completed.usage)
        assertEquals(1, tool.calls)
        assertEquals(2, executor.prompts.size)
        verify(executor).close()
    }

    @Test
    fun `model failure after tool execution retains accumulated usage`() = runBlocking {
        val failure = IllegalStateException("Model unavailable")
        val steps = ArrayDeque<suspend () -> Unit>(listOf({}, { throw failure }))
        val executor =
            spy(QueuedPromptExecutor(toolRequest(), beforeResponse = { steps.removeFirst()() }))
        val tool = UsageCountingTool()

        val result = runAgent(executor, ToolRegistry { tool(tool) })

        val failed = assertIs<AgentRunResult.Failed>(result)
        assertSame(failure, failed.cause)
        assertEquals(AgentUsageStats(1, 10, 3), failed.usage)
        assertEquals(1, tool.calls)
        verify(executor).close()
    }

    @Test
    fun `failed tool call is counted when model recovers`() = runBlocking {
        val executor = spy(QueuedPromptExecutor(toolRequest(), finalResponse()))

        val result = runAgent(executor, ToolRegistry { tool(UsageFailingTool()) })

        val completed = assertIs<AgentRunResult.Completed<String>>(result)
        assertEquals("Finished", completed.output)
        assertEquals(AgentUsageStats(1, 30, 7), completed.usage)
        verify(executor).close()
    }

    @Test
    fun `model failure before first response returns zero usage`() = runBlocking {
        val failure = IllegalStateException("Model unavailable")
        val executor = spy(QueuedPromptExecutor(beforeResponse = { throw failure }))

        val result = runAgent(executor)

        val failed = assertIs<AgentRunResult.Failed>(result)
        assertSame(failure, failed.cause)
        assertEquals(AgentUsageStats(), failed.usage)
        verify(executor).close()
    }

    @Test
    fun `cancellation propagates instead of returning failed result`() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        val executor = spy(QueuedPromptExecutor(beforeResponse = { throw cancellation }))

        val thrown = assertFailsWith<CancellationException> { runAgent(executor) }

        assertSame(cancellation, thrown)
        verify(executor).close()
    }

    private suspend fun runAgent(
        executor: PromptExecutor,
        tools: ToolRegistry = ToolRegistry.EMPTY,
        outputSchema: JsonObject? = null,
    ): AgentRunResult<String> {
        val config =
            KoogConfig(
                "unused",
                "Test",
                "",
                "test",
                "test",
                "low",
                "test",
                "test",
                "medium",
                "test",
                "test",
                "high",
                "image",
                10,
            )
        return mockStatic(
                Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
            )
            .use { model ->
                model.`when`<PromptExecutor> { config.openRouterExecutor() }.thenReturn(executor)
                KoogAgent(config, "delegate")
                    .run(
                        RunId(ConversationId("C123", "thread"), "run-1"),
                        tools,
                        config.normalProfile,
                        { "System instructions" },
                        { "Inspect input" },
                        outputSchema,
                    )
            }
    }

    private fun toolRequest() =
        Message.Assistant(
            parts = listOf(MessagePart.Tool.Call("call-1", "inspect", "{}")),
            metaInfo = ResponseMetaInfo.Empty.copy(inputTokensCount = 10, outputTokensCount = 3),
        )

    private fun finalResponse() =
        Message.Assistant(
            "Finished",
            ResponseMetaInfo.Empty.copy(inputTokensCount = 20, outputTokensCount = 4),
        )
}
