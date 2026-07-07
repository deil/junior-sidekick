package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.utils.trimEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

fun interface SubagentRunner {
    suspend fun run(
        ctx: TurnContext,
        prompt: String,
    ): String
}

class TaskTools(
    private val runner: SubagentRunner,
    private val ctx: TurnContext,
    private val chat: ChatPlatformAdapter,
) : ToolSet {
    @Tool(TOOL_TASK)
    @LLMDescription("Launch a subagent to perform a delegated task in a fresh context, and return its final answer")
    fun task(
        @LLMDescription("Short 3-5 word label for the task")
        description: String,
        @LLMDescription("The task for the subagent to perform")
        prompt: String,
        @LLMDescription("The type of specialized agent to use for this task. Defaults to 'default'")
        subagent_type: String = "default",
    ): String {
        validateTaskArgument(description, "description", TASK_DESCRIPTION_MAX_CHARS)
        validateTaskArgument(prompt, "prompt", TASK_PROMPT_MAX_CHARS)
        validateTaskArgument(subagent_type, "subagent_type", null)

        chat.activity.`continue`("${subagent_type.trim()} task - ${description.trim()}")

        try {
            val result =
                runBlocking {
                    runner.run(
                        ctx = ctx,
                        prompt = prompt.trim(),
                    )
                }

            return result
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
        val trimmed = value.trim()
        validate(trimmed.isNotBlank()) { "Task $name must not be blank" }
        validate(maxChars == null || trimmed.length <= maxChars) { "Task $name must be at most $maxChars characters" }
    }

    companion object {
        const val TOOL_TASK = "Task"
        const val TASK_DESCRIPTION_MAX_CHARS = 80
        const val TASK_PROMPT_MAX_CHARS = 10_000
    }
}
