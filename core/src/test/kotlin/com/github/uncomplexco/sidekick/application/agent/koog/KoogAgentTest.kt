package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class KoogAgentTest {
    @Test
    fun `string output is raw text and omitted system prompt adds no message`() = runBlocking {
        val config = config()
        val executor =
            spy(
                QueuedPromptExecutor(
                    Message.Assistant(
                        "  Plain text\n",
                        ResponseMetaInfo.Empty.copy(inputTokensCount = 12, outputTokensCount = 4),
                    )
                )
            )

        val result =
            mockStatic(
                    Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
                )
                .use { model ->
                    model
                        .`when`<PromptExecutor> { config.openRouterExecutor() }
                        .thenReturn(executor)
                    KoogAgent(config, "summary")
                        .runSimple<String>(
                            runId = RunId(ConversationId("C123", "thread"), "run-1"),
                            model = config.fastProfile,
                            input = { "Summarize this" },
                        )
                }

        val completed = assertIs<AgentRunResult.Completed<String>>(result)
        assertEquals("  Plain text\n", completed.output)
        assertEquals(AgentUsageStats(inputTokenCount = 12, outputTokenCount = 4), completed.usage)
        val message = executor.prompts.single().messages.single()
        assertEquals("Summarize this", assertIs<Message.User>(message).textContent())
        verify(executor).close()
    }

    @Test
    fun `simple run sends caller prompts without tools and returns structured result`() =
        runBlocking {
            val config = config()
            val profile = config.fastProfile
            val runId = RunId(ConversationId("C123", "thread"), "run-1")
            val executor =
                spy(
                    QueuedPromptExecutor(
                        Message.Assistant(
                            """{"shouldReply":true}""",
                            ResponseMetaInfo.Empty.copy(
                                inputTokensCount = 20,
                                outputTokensCount = 5,
                            ),
                        )
                    )
                )

            val result = runStructured(executor)

            val completed = assertIs<AgentRunResult.Completed<Decision>>(result)
            assertEquals(Decision(true), completed.output)
            assertEquals(
                AgentUsageStats(inputTokenCount = 20, outputTokenCount = 5),
                completed.usage,
            )
            val prompt = executor.prompts.single()
            assertContains(
                prompt.messages.filterIsInstance<Message.System>().joinToString {
                    it.textContent()
                },
                "System instructions",
            )
            assertContains(
                prompt.messages.filterIsInstance<Message.User>().joinToString { it.textContent() },
                "Input text",
            )
            assertEquals(
                config.openRouterParams(profile, runId.conversationId).additionalProperties,
                (prompt.params as OpenRouterParams).additionalProperties,
            )
            verify(executor)
                .execute(
                    prompt,
                    LLModel(LLMProvider.OpenRouter, profile.model, config.modelCapabilities()),
                    emptyList(),
                )
            verify(executor).close()
        }

    @Test
    fun `simple run returns failed result with unreported usage and closes executor`() =
        runBlocking {
            val config = config()
            val failure = IllegalStateException("Model unavailable")
            val executor = spy(QueuedPromptExecutor(beforeResponse = { throw failure }))
            val agent = KoogAgent(config, "classifier")

            val result =
                mockStatic(
                        Class.forName(
                            "com.github.uncomplexco.sidekick.application.agent.OpenRouterKt"
                        )
                    )
                    .use { model ->
                        model
                            .`when`<PromptExecutor> { config.openRouterExecutor() }
                            .thenReturn(executor)
                        agent.runSimple<Decision>(
                            RunId(ConversationId("C123", "thread"), "run-1"),
                            config.fastProfile,
                            { "System instructions" },
                            { "Input text" },
                        )
                    }

            val failed = assertIs<AgentRunResult.Failed>(result)
            assertSame(failure, failed.cause)
            assertEquals(AgentUsageStats(), failed.usage)
            verify(executor).close()
        }

    @Test
    fun `structured parsing failure retains response usage`() = runBlocking {
        val executor =
            spy(
                QueuedPromptExecutor(
                    Message.Assistant(
                        "not JSON",
                        ResponseMetaInfo.Empty.copy(inputTokensCount = 30, outputTokensCount = 8),
                    )
                )
            )

        val result = runStructured(executor)

        val failed = assertIs<AgentRunResult.Failed>(result)
        assertEquals(AgentUsageStats(inputTokenCount = 30, outputTokenCount = 8), failed.usage)
        verify(executor).close()
    }

    @Test
    fun `cancellation propagates and closes executor`() = runBlocking {
        val cancellation = CancellationException("Cancelled")
        val executor = spy(QueuedPromptExecutor(beforeResponse = { throw cancellation }))

        val thrown = assertFailsWith<CancellationException> { runStructured(executor) }

        assertSame(cancellation, thrown)
        verify(executor).close()
    }

    private suspend fun runStructured(executor: QueuedPromptExecutor): AgentRunResult<Decision> {
        val config = config()
        return mockStatic(
                Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
            )
            .use { model ->
                model.`when`<PromptExecutor> { config.openRouterExecutor() }.thenReturn(executor)
                KoogAgent(config, "classifier")
                    .runSimple<Decision>(
                        RunId(ConversationId("C123", "thread"), "run-1"),
                        config.fastProfile,
                        { "System instructions" },
                        { "Input text" },
                    )
            }
    }

    private fun config() =
        KoogConfig(
            "unused",
            "Test",
            "",
            "test-fast",
            "test",
            "low",
            "test-normal",
            "test",
            "medium",
            "test-ultrathink",
            "test",
            "high",
            "test-image",
            10,
        )

    @Serializable data class Decision(val shouldReply: Boolean)
}
