package com.github.uncomplexco.sidekick.application.turn.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SidekickStrategyTest {
    @Test
    fun `executes tool call from mixed follow-up response before finishing`() =
        runBlocking {
            val toolCalls = mutableListOf<String>()
            val tool = RecordingTool(toolCalls)
            val executor =
                QueuedPromptExecutor(
                    Message.Assistant(MessagePart.Tool.Call("call-1", tool.name, """{"value":"first"}"""), ResponseMetaInfo.Empty),
                    Message.Assistant(
                        parts =
                            listOf(
                                MessagePart.Text("Still working."),
                                MessagePart.Tool.Call("call-2", tool.name, """{"value":"second"}"""),
                            ),
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                    Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                )
            val agent =
                AIAgent(
                    strategy = sidekickStrategy(),
                    promptExecutor = executor,
                    agentConfig =
                        AIAgentConfig(
                            prompt = prompt("test") {},
                            model = LLModel(LLMProvider.OpenRouter, "test", listOf(LLMCapability.Tools)),
                            maxAgentIterations = 10,
                        ),
                    toolRegistry = ToolRegistry { tool(tool) },
                )

            val result = agent.run("Start", null)

            assertEquals(listOf("first", "second"), toolCalls)
            assertEquals("Finished.", result)
        }
}

private class RecordingTool(
    private val calls: MutableList<String>,
) : SimpleTool<RecordingTool.Args>(
        argsType = typeToken<Args>(),
        name = "record",
        description = "Record a value",
    ) {
    @Serializable
    data class Args(
        val value: String,
    )

    override suspend fun execute(args: Args): String {
        calls += args.value
        return args.value
    }
}

private class QueuedPromptExecutor(
    vararg responses: Message.Assistant,
) : PromptExecutor() {
    private val responses = ArrayDeque(responses.toList())

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = responses.removeFirst()

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = emptyFlow()

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("Moderation is not used")

    override fun close() = Unit
}
