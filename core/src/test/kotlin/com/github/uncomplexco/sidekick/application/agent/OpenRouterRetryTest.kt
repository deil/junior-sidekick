package com.github.uncomplexco.sidekick.application.agent

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.retry.toRetryingClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import com.github.uncomplexco.sidekick.testing.QueuedOpenRouterServer
import com.github.uncomplexco.sidekick.testing.QueuedOpenRouterServer.Response
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

private const val MALFORMED_ARGS =
    """{"query":"test-channel","limit":10,"cursor":,"includeArchived":false}"""
private const val VALID_ARGS =
    """{"query":"test-channel","limit":10,"cursor":null,"includeArchived":false}"""

class OpenRouterRetryTest {
    private val model =
        LLModel(
            LLMProvider.OpenRouter,
            "z-ai/glm-5.3",
            listOf(LLMCapability.Tools, LLMCapability.Completion),
        )
    private val prompt = prompt("retry-test") { user("Find test-channel") }

    @Test
    fun `malformed tool arguments retry the same model request and return the corrected call`() =
        runBlocking {
            // Arrange
            QueuedOpenRouterServer(toolResponse(MALFORMED_ARGS), toolResponse(VALID_ARGS)).use {
                server ->
                client(server).use { client ->
                    // Act
                    val response = client.execute(prompt, model, emptyList())

                    // Assert
                    assertEquals(
                        listOf(MessagePart.Tool.Call("call-1", "slackChannelsList", VALID_ARGS)),
                        response.parts,
                    )
                    assertEquals(2, server.requests.size)
                    assertEquals(
                        1,
                        server.requests.distinct().size,
                        "Retries must not append or replay agent history",
                    )
                }
            }
        }

    @Test
    fun `persistent malformed arguments fail after the existing five attempt budget`() =
        runBlocking {
            // Arrange
            QueuedOpenRouterServer(*Array(5) { toolResponse(MALFORMED_ARGS) }).use { server ->
                client(server).use { client ->
                    // Act
                    assertFailsWith<SerializationException> {
                        client.execute(prompt, model, emptyList())
                    }

                    // Assert
                    assertEquals(5, server.requests.size)
                }
            }
        }

    @Test
    fun `authentication errors are not retried`() = runBlocking {
        // Arrange
        QueuedOpenRouterServer(
                Response("""{"error":{"message":"Invalid API key","code":401}}""", 401)
            )
            .use { server ->
                client(server).use { client ->
                    // Act
                    assertFails { client.execute(prompt, model, emptyList()) }

                    // Assert
                    assertEquals(1, server.requests.size)
                }
            }
    }

    private fun client(server: QueuedOpenRouterServer) =
        OpenRouterLLMClient(
                apiKey = "test-key",
                settings = OpenRouterClientSettings(baseUrl = server.baseUrl),
                httpClientFactory = HttpClientFactoryResolver.resolve(),
            )
            .toRetryingClient(
                OPEN_ROUTER_RETRY_CONFIG.copy(
                    initialDelay = 1.milliseconds,
                    maxDelay = 1.milliseconds,
                )
            )

    private fun toolResponse(arguments: String) =
        Response(
            """
        {"id":"test","object":"chat.completion","created":1794094917,"model":"z-ai/glm-5.3",
         "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
         "tool_calls":[{"id":"call-1","type":"function","function":{"name":"slackChannelsList","arguments":${JsonPrimitive(arguments)}}}]}}],
         "usage":{"prompt_tokens":10,"completion_tokens":10,"total_tokens":20}}
    """
                .trimIndent()
        )
}
