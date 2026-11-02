package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class KoogAgentRunTest {
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
        executor: QueuedPromptExecutor,
        tools: ToolRegistry = ToolRegistry.EMPTY,
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
