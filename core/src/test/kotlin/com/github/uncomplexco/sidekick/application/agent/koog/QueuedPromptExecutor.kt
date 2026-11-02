package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/** Model-edge test double shared by strategy and agent tests. */
internal class QueuedPromptExecutor(
    vararg responses: Message.Assistant,
    private val beforeResponse: suspend (Int) -> Unit = {},
) : PromptExecutor() {
    private val responses = ArrayDeque(responses.toList())
    val prompts = mutableListOf<Prompt>()

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant {
        prompts += prompt
        beforeResponse(prompts.size)
        return responses.removeFirst()
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = emptyFlow()

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        error("Moderation is not used")

    override fun close() = Unit
}

internal class UsageCountingTool :
    SimpleTool<UsageCountingTool.Args>(typeToken<Args>(), "inspect", "Inspect input") {
    var calls = 0
        private set

    @Serializable class Args

    override suspend fun execute(args: Args): String {
        calls++
        return "Inspected"
    }
}

internal class UsageFailingTool :
    SimpleTool<UsageCountingTool.Args>(
        typeToken<UsageCountingTool.Args>(),
        "inspect",
        "Inspect input",
    ) {
    override suspend fun execute(args: UsageCountingTool.Args): String =
        throw ToolException.ValidationFailure("Cannot inspect input")
}
