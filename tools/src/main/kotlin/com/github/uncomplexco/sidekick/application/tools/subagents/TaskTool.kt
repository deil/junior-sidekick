package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.validate
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

fun interface SubagentRunner {
    suspend fun run(
        ctx: TurnContext,
        subagentType: String,
        prompt: String,
    ): String
}

class TaskTool(
    private val runner: SubagentRunner,
    private val ctx: TurnContext,
    private val chat: ChatPlatformAdapter,
    availableSubagents: List<Subagent>,
) : Tool<TaskTool.Args, String>(
        argsType = typeToken<Args>(),
        resultType = typeToken<String>(),
        name = TOOL_TASK,
        description = taskDescription(availableSubagents),
    ) {
    private val availableSubagentTypes = availableSubagents.map { it.name }.toSet()

    @Serializable
    data class Args(
        @property:LLMDescription("Short 3-5 word label for the task")
        val description: String,
        @property:LLMDescription("The task for the subagent to perform")
        val prompt: String,
        @property:LLMDescription("The type of specialized agent to use for this task. Defaults to 'general'")
        val subagent_type: String = "general",
    )

    override suspend fun execute(args: Args): String {
        val description = args.description.trim()
        val subagentType = args.subagent_type.trim()

        validateTaskArgument(description, "description", TASK_DESCRIPTION_MAX_CHARS)
        validateTaskArgument(args.prompt, "prompt", TASK_PROMPT_MAX_CHARS)
        validateTaskArgument(subagentType, "subagent_type", null)
        validate(subagentType in availableSubagentTypes) { "Unknown subagent_type: $subagentType" }

        chat.activity.`continue`("$subagentType task - $description")

        try {
            return runner.run(
                ctx = ctx,
                subagentType = subagentType,
                prompt = args.prompt,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Subagent failed")
        } finally {
            chat.activity.`continue`()
        }
    }

    private fun validateTaskArgument(
        value: String,
        name: String,
        maxChars: Int?,
    ) {
        validate(value.isNotBlank()) { "Task $name must not be blank" }
        validate(maxChars == null || value.length <= maxChars) { "Task $name must be at most $maxChars characters" }
    }

    companion object {
        const val TOOL_TASK = "Task"
        const val TASK_DESCRIPTION_MAX_CHARS = 80
        const val TASK_PROMPT_MAX_CHARS = 10_000
    }
}

fun taskDescription(availableSubagents: List<Subagent>): String =
    buildString {
        appendLine("Launch a subagent to perform a delegated task in a fresh context, and return its final answer.")
        appendLine()
        appendLine("Available agent types and the tools they have access to:")
        availableSubagents.forEach { subagent ->
            appendLine("  - ${subagent.name}: ${subagent.description}")
        }
    }
