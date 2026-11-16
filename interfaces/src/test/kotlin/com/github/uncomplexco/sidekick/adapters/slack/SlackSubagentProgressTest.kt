package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.chat.SubagentProgress
import com.github.uncomplexco.sidekick.application.chat.SubagentStatus
import com.github.uncomplexco.sidekick.application.chat.TodoProgress
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.tools.todos.TodoTool
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@OptIn(ExperimentalCoroutinesApi::class)
class SlackSubagentProgressTest {
    private val todos =
        TodoProgress("Fix the bug\n\n∗ Implement", "_todos as of 12:34:56 (just now)_")
    private val agent = SubagentProgress("agent-1", "Find owners", "general", SubagentStatus.ACTIVE)

    @Test
    fun `subagent lifecycle events render between the todo list and status line`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                "C123",
                ChatThreadId("1700000000.000", true),
                SidekickCoroutineScope(backgroundScope),
            )
        output.begin()
        output.reportTodos(todos)

        output.reportSubagent(agent)
        output.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))

        assertEquals(1, api.replies.size)
        assertEquals(
            listOf(
                "Fix the bug\n\n∗ Implement\n\n*Subagents*\n∗ Find owners (general)\n\n_todos as of 12:34:56 (just now)_",
                "Fix the bug\n\n∗ Implement\n\n*Subagents*\n✓ Find owners (general)\n\n_todos as of 12:34:56 (just now)_",
            ),
            api.edits.map { it.text },
        )
        assertEquals(listOf("1700000001.000", "1700000001.000"), api.edits.map { it.ts })
    }

    @Test
    fun `Todo tool updates retain completed subagents in the same message`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val output =
            SlackTurnOutput(
                api.client,
                "C123",
                ChatThreadId("1700000000.000", true),
                SidekickCoroutineScope(backgroundScope),
            )
        val tool = TodoTool(output)
        output.begin()
        output.reportSubagent(agent)
        output.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))

        tool.execute(
            TodoTool.Args(
                "Fix the bug",
                listOf(
                    TodoTool.Item("Implement", TodoTool.Status.COMPLETED, TodoTool.Priority.HIGH)
                ),
            )
        )

        assertEquals(1, api.replies.size)
        assertContains(
            api.edits.last().text,
            "Fix the bug\n\n✓ Implement\n\n*Subagents*\n✓ Find owners (general)\n\n_todos as of ",
        )
        assertEquals("1700000001.000", api.edits.last().ts)
    }

    @Test
    fun `tasks with the same label retain independent statuses in invocation order`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.reportTodos(todos, null)
        messages.reportSubagent(agent)
        messages.reportSubagent(agent.copy(id = "agent-2"))

        messages.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))

        assertEquals(
            "Fix the bug\n\n∗ Implement\n\n*Subagents*\n✓ Find owners (general)\n∗ Find owners (general)\n\n_todos as of 12:34:56 (just now)_",
            api.edits.last().text,
        )
    }

    @Test
    fun `minute refresh preserves completed subagents and updates only todo age`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        var refreshed = todos
        messages.reportTodos(todos) { refreshed }
        messages.reportSubagent(agent)
        messages.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))
        runCurrent()

        refreshed =
            TodoProgress("Fix the bug\n\n∗ Implement", "_todos as of 12:34:56 (1 minute ago)_")
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, api.replies.size)
        assertEquals(3, api.edits.size)
        assertEquals(
            "Fix the bug\n\n∗ Implement\n\n*Subagents*\n✓ Find owners (general)\n\n_todos as of 12:34:56 (1 minute ago)_",
            api.edits.last().text,
        )
    }

    @ParameterizedTest(name = "{0} subagent events render the {1} label")
    @CsvSource("FAILED,failed", "CANCELLED,cancelled")
    fun `unsuccessful subagent events render their status labels`(
        status: SubagentStatus,
        label: String,
    ) = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.reportSubagent(agent)

        messages.reportSubagent(agent.copy(status = status))

        assertEquals("*Subagents*\n× Find owners (general, $label)", api.edits.single().text)
    }

    @Test
    fun `subagent completion updates the message even after all todos are completed`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.reportTodos(
            TodoProgress("Fix the bug\n\n✓ Implement", "_todos as of 12:34:56_"),
            null,
        )
        messages.reportSubagent(agent)

        messages.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))

        assertEquals(
            "Fix the bug\n\n✓ Implement\n\n*Subagents*\n✓ Find owners (general)\n\n_todos as of 12:34:56_",
            api.edits.last().text,
        )
    }

    @Test
    fun `beginning another turn clears the previous subagent list`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(
                api.client,
                "C123",
                "1700000000.000",
                SidekickCoroutineScope(backgroundScope),
            )
        messages.begin()
        messages.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))
        messages.end()
        messages.begin()

        messages.reportTodos(todos, null)

        assertEquals(
            "Fix the bug\n\n∗ Implement\n\n_todos as of 12:34:56 (just now)_",
            api.replies.last().text,
        )
        assertEquals(2, api.replies.size)
    }

    @Test
    fun `subagent events after the turn ends neither post nor edit a message`() = runTest {
        val api = SlackActivityApi { testScheduler.currentTime }
        val messages =
            SlackProgressMessages(api.client, "C123", null, SidekickCoroutineScope(backgroundScope))
        messages.begin()
        messages.reportSubagent(agent)
        messages.end()

        messages.reportSubagent(agent.copy(status = SubagentStatus.COMPLETED))

        assertEquals(
            listOf("*Subagents*\n∗ Find owners (general)"),
            api.replies.map { it.text },
        )
        assertEquals(emptyList(), api.edits)
    }
}
