package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.typeToken
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

class SidekickStrategyTest {
    @Test
    fun `batch appends consecutive user messages before one model request`() = runBlocking {
        // Arrange
        val executor = QueuedPromptExecutor(Message.Assistant("Finished.", ResponseMetaInfo.Empty))
        var drains = 0
        val agent =
            AIAgent(
                strategy =
                    sidekickStrategy {
                        drains++
                        emptyList()
                    },
                promptExecutor = executor,
                agentConfig =
                    AIAgentConfig(
                        prompt = prompt("test") { system("System instructions") },
                        model =
                            LLModel(LLMProvider.OpenRouter, "test", listOf(LLMCapability.Tools)),
                        maxAgentIterations = 10,
                    ),
                toolRegistry = ToolRegistry.EMPTY,
            )

        // Act
        val result =
            agent.run(listOf("Check production", "Actually, staging", "Only the API service"), null)

        // Assert
        val sent = executor.prompts.single()
        assertEquals(
            listOf("Check production", "Actually, staging", "Only the API service"),
            sent.messages.filterIsInstance<Message.User>().map { it.textContent() },
        )
        assertEquals("Finished.", result)
        assertEquals(0, drains, "A final response must not drain input for a new turn")
    }

    @Test
    fun `steering follows every complete tool batch and appends separate users after all results`() =
        runBlocking {
            // Arrange
            val calls = mutableListOf<String>()
            val tool = RecordingTool(calls)
            val executor =
                QueuedPromptExecutor(
                    Message.Assistant(
                        parts =
                            listOf(
                                MessagePart.Tool.Call("call-1", tool.name, """{"value":"first"}"""),
                                MessagePart.Tool.Call(
                                    "call-2",
                                    tool.name,
                                    """{"value":"second"}""",
                                ),
                            ),
                        metaInfo = ResponseMetaInfo.Empty,
                    ),
                    Message.Assistant(
                        MessagePart.Tool.Call("call-3", tool.name, """{"value":"third"}"""),
                        ResponseMetaInfo.Empty,
                    ),
                    Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                )
            val callsAtDrain = mutableListOf<List<String>>()
            val agent =
                testAgent(executor, tool) {
                    callsAtDrain += calls.toList()
                    if (callsAtDrain.size == 1) listOf("Actually, staging", "Only the API service")
                    else emptyList()
                }

            // Act
            val result = agent.run(listOf("Start"), null)

            // Assert
            assertEquals("Finished.", result)
            assertEquals(
                listOf(listOf("first", "second"), listOf("first", "second", "third")),
                callsAtDrain,
            )
            val followUp = executor.prompts[1].messages.takeLast(3)
            assertEquals(
                listOf("call-1", "call-2"),
                followUp[0].parts.filterIsInstance<MessagePart.Tool.Result>().map { it.id },
            )
            assertEquals(
                listOf("Actually, staging", "Only the API service"),
                followUp.drop(1).map { (it as Message.User).textContent() },
            )
            assertEquals(
                listOf("call-3"),
                executor.prompts[2]
                    .messages
                    .last()
                    .parts
                    .filterIsInstance<MessagePart.Tool.Result>()
                    .map { it.id },
            )
        }

    @Test
    fun `executes tool call from mixed follow-up response before finishing`() = runBlocking {
        val toolCalls = mutableListOf<String>()
        val tool = RecordingTool(toolCalls)
        val executor =
            QueuedPromptExecutor(
                Message.Assistant(
                    MessagePart.Tool.Call("call-1", tool.name, """{"value":"first"}"""),
                    ResponseMetaInfo.Empty,
                ),
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
                        model =
                            LLModel(LLMProvider.OpenRouter, "test", listOf(LLMCapability.Tools)),
                        maxAgentIterations = 10,
                    ),
                toolRegistry = ToolRegistry { tool(tool) },
            )

        val result = agent.run(listOf("Start"), null)

        assertEquals(listOf("first", "second"), toolCalls)
        assertEquals("Finished.", result)
    }
}

private fun testAgent(
    executor: QueuedPromptExecutor,
    tool: RecordingTool,
    steer: suspend () -> List<String>,
) =
    AIAgent(
        strategy = sidekickStrategy(steer),
        promptExecutor = executor,
        agentConfig =
            AIAgentConfig(
                prompt = prompt("test") {},
                model = LLModel(LLMProvider.OpenRouter, "test", listOf(LLMCapability.Tools)),
                maxAgentIterations = 10,
            ),
        toolRegistry = ToolRegistry { tool(tool) },
    )

private class RecordingTool(private val calls: MutableList<String>) :
    SimpleTool<RecordingTool.Args>(
        argsType = typeToken<Args>(),
        name = "record",
        description = "Record a value",
    ) {
    @Serializable data class Args(val value: String)

    override suspend fun execute(args: Args): String {
        calls += args.value
        return args.value
    }
}
