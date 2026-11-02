package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class KoogAgentTest {
    @Test
    fun `simple run sends caller prompts without tools and returns structured result`() =
        runBlocking {
            val config = config()
            val profile = config.fastProfile
            val runId = RunId(ConversationId("C123", "thread"), "run-1")
            val executor =
                spy(
                    QueuedPromptExecutor(
                        Message.Assistant("""{"shouldReply":true}""", ResponseMetaInfo.Empty)
                    )
                )
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
                            runId,
                            profile,
                            { "System instructions" },
                            { "Input text" },
                        )
                    }

            assertEquals(Decision(true), result)
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
    fun `simple run propagates model failure and closes executor`() = runBlocking {
        val config = config()
        val failure = IllegalStateException("Model unavailable")
        val executor = spy(QueuedPromptExecutor(beforeResponse = { throw failure }))
        val agent = KoogAgent(config, "classifier")

        val thrown =
            mockStatic(
                    Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
                )
                .use { model ->
                    model
                        .`when`<PromptExecutor> { config.openRouterExecutor() }
                        .thenReturn(executor)
                    assertFailsWith<IllegalStateException> {
                        agent.runSimple<Decision>(
                            RunId(ConversationId("C123", "thread"), "run-1"),
                            config.fastProfile,
                            { "System instructions" },
                            { "Input text" },
                        )
                    }
                }

        assertSame(failure, thrown)
        verify(executor).close()
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
