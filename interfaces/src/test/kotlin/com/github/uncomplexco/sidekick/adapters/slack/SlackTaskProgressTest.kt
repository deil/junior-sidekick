package com.github.uncomplexco.sidekick.adapters.slack

import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.chat.TodoProgress
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.tools.subagents.Subagent
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.testing.CompletingSubagentRunner
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class SlackTaskProgressTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `executing Task adds an active Slack row and completes it in the same todo message`() =
        runTest {
            val api = SlackActivityApi { testScheduler.currentTime }
            val output =
                SlackTurnOutput(
                    api.client,
                    "C123",
                    ChatThreadId("1700000000.000", true),
                    SidekickCoroutineScope(backgroundScope),
                )
            val task =
                TaskTool(
                    runner = CompletingSubagentRunner("Alice owns this service"),
                    ctx = turnContext(),
                    activityReporter = output,
                    availableSubagents =
                        listOf(Subagent("general", "General-purpose subagent", "Prompt")),
                    onSubagentCompleted = {},
                    bindTools = { ToolRegistry.EMPTY },
                )
            output.begin()
            output.reportTodos(TodoProgress("Fix the bug\n\n∗ Implement", "_todos as of 12:34:56_"))

            val result = task.execute(TaskTool.Args("Find owners", "Find the service owner"))

            assertEquals("Alice owns this service", result)
            assertEquals(
                listOf("Fix the bug\n\n∗ Implement\n\n_todos as of 12:34:56_"),
                api.replies.map { it.text },
            )
            assertEquals(
                listOf(
                    "Fix the bug\n\n∗ Implement\n\n*Subagents*\n∗ Find owners (general)\n\n_todos as of 12:34:56_",
                    "Fix the bug\n\n∗ Implement\n\n*Subagents*\n✓ Find owners (general)\n\n_todos as of 12:34:56_",
                ),
                api.edits.map { it.text },
            )
            assertEquals(listOf("1700000001.000", "1700000001.000"), api.edits.map { it.ts })
        }

    private fun turnContext() =
        TurnContext(
            conversation =
                ConversationContext(
                    conversationId = ConversationId("C123", "1700000000.000"),
                    virtualPaths = VirtualPaths(dir, dir, dir, dir, dir),
                    history =
                        ConversationHistory(emptyList(), emptyList(), hasKoogMessages = false),
                    mcpServers = emptyList(),
                ),
            turnId = "turn-1",
            lastMessageId = "message-1",
            sessionFiles = emptyList(),
            aiModelProfile = AiModelProfile.NORMAL,
        )
}
