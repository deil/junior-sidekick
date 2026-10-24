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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

class SidekickStrategyTest {
    @Test
    fun `stop during a model request prevents its requested tools from starting`() = runBlocking {
        withTimeout(10_000) {
            // Arrange
            var stopped = false
            val requested = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val tool = RecordingTool(calls)
            val executor =
                QueuedPromptExecutor(
                    toolResponse("first"),
                    Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                    beforeResponse = { request ->
                        if (request == 1) {
                            requested.complete(Unit)
                            respond.await()
                        }
                    },
                )
            val agent = testAgent(executor, tool, stopRequested = { stopped }) { emptyList() }
            val execution = async { agent.run(listOf("Start"), null) }
            requested.await()

            // Act
            stopped = true
            respond.complete(Unit)
            val result = execution.await()

            // Assert
            assertEquals(AgentOutcome.Stopped, result)
            assertEquals(emptyList(), calls, "Tools requested after Stop must never start")
            assertEquals(1, executor.prompts.size, "Stopping must not send another model request")
        }
    }

    @Test
    fun `stop during tools lets the whole running batch finish without another model request`() =
        runBlocking {
            withTimeout(10_000) {
                // Arrange
                var stopped = false
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val calls = mutableListOf<String>()
                val tool =
                    RecordingTool(calls) { value ->
                        if (value == "first") {
                            started.complete(Unit)
                            finish.await()
                        }
                    }
                val executor =
                    QueuedPromptExecutor(
                        toolResponse("first", "second"),
                        Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                    )
                val agent = testAgent(executor, tool, stopRequested = { stopped }) { emptyList() }
                val execution = async { agent.run(listOf("Start"), null) }
                started.await()

                // Act
                stopped = true
                assertEquals(false, execution.isCompleted, "The running batch must not be killed")
                finish.complete(Unit)
                val result = execution.await()

                // Assert
                assertEquals(AgentOutcome.Stopped, result)
                assertEquals(listOf("first", "second"), calls.sorted())
                assertEquals(
                    1,
                    executor.prompts.size,
                    "Finished tool results must not be sent to the model after Stop",
                )
            }
        }

    @Test
    fun `stop during a follow-up model request prevents the next tool batch`() = runBlocking {
        withTimeout(10_000) {
            // Arrange
            var stopped = false
            val requested = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val tool = RecordingTool(calls)
            val executor =
                QueuedPromptExecutor(
                    toolResponse("first"),
                    toolResponse("second"),
                    Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                    beforeResponse = { request ->
                        if (request == 2) {
                            requested.complete(Unit)
                            respond.await()
                        }
                    },
                )
            val agent = testAgent(executor, tool, stopRequested = { stopped }) { emptyList() }
            val execution = async { agent.run(listOf("Start"), null) }
            requested.await()

            // Act
            stopped = true
            respond.complete(Unit)
            val result = execution.await()

            // Assert
            assertEquals(AgentOutcome.Stopped, result)
            assertEquals(listOf("first"), calls)
            assertEquals(2, executor.prompts.size)
        }
    }

    @Test
    fun `final answer wins when stop arrives during a model request`() = runBlocking {
        withTimeout(10_000) {
            // Arrange
            var stopped = false
            var drains = 0
            val requested = CompletableDeferred<Unit>()
            val respond = CompletableDeferred<Unit>()
            val executor =
                QueuedPromptExecutor(
                    Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                    beforeResponse = {
                        requested.complete(Unit)
                        respond.await()
                    },
                )
            val agent =
                testAgent(executor, RecordingTool(mutableListOf()), stopRequested = { stopped }) {
                    drains++
                    emptyList()
                }
            val execution = async { agent.run(listOf("Start"), null) }
            requested.await()

            // Act
            stopped = true
            respond.complete(Unit)
            val result = execution.await()

            // Assert
            assertEquals(AgentOutcome.Completed("Finished."), result)
            assertEquals(0, drains)
            assertEquals(1, executor.prompts.size)
        }
    }

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
        assertEquals(AgentOutcome.Completed("Finished."), result)
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
            assertEquals(AgentOutcome.Completed("Finished."), result)
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
        assertEquals(AgentOutcome.Completed("Finished."), result)
    }
}

private fun testAgent(
    executor: QueuedPromptExecutor,
    tool: RecordingTool,
    stopRequested: () -> Boolean = { false },
    steer: suspend () -> List<String>,
) =
    AIAgent(
        strategy = sidekickStrategy(stopRequested = stopRequested, steer = steer),
        promptExecutor = executor,
        agentConfig =
            AIAgentConfig(
                prompt = prompt("test") {},
                model = LLModel(LLMProvider.OpenRouter, "test", listOf(LLMCapability.Tools)),
                maxAgentIterations = 10,
            ),
        toolRegistry = ToolRegistry { tool(tool) },
    )

private fun toolResponse(vararg values: String) =
    Message.Assistant(
        parts =
            values.mapIndexed { index, value ->
                MessagePart.Tool.Call("call-$index", "record", """{"value":"$value"}""")
            },
        metaInfo = ResponseMetaInfo.Empty,
    )

private class RecordingTool(
    private val calls: MutableList<String>,
    private val beforeExecute: suspend (String) -> Unit = {},
) :
    SimpleTool<RecordingTool.Args>(
        argsType = typeToken<Args>(),
        name = "record",
        description = "Record a value",
    ) {
    @Serializable data class Args(val value: String)

    override suspend fun execute(args: Args): String {
        beforeExecute(args.value)
        calls += args.value
        return args.value
    }
}
