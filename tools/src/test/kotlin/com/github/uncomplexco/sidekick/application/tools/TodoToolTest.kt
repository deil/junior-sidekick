package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.application.tools.todos.TodoTool
import com.github.uncomplexco.sidekick.testing.CatalogOutput
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class TodoToolTest {
    private var now = Instant.parse("2026-11-04T12:34:56Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }

    @Test
    fun `result is the updated JSON array without an envelope`() = runBlocking {
        // Arrange
        val tool = TodoTool()
        val args =
            TodoTool.Args(
                "Fix the bug and verify it",
                listOf(
                    TodoTool.Item("Fix bug", TodoTool.Status.COMPLETED, TodoTool.Priority.HIGH),
                    TodoTool.Item(
                        "Add tests",
                        TodoTool.Status.IN_PROGRESS,
                        TodoTool.Priority.MEDIUM,
                    ),
                ),
            )

        // Act
        val result = tool.execute(args)

        // Assert
        assertEquals(
            """[{"content":"Fix bug","status":"completed","priority":"high"},""" +
                """{"content":"Add tests","status":"in_progress","priority":"medium"}]""",
            Json.encodeToString(result),
        )
    }

    @Test
    fun `Todo publishes goal and status icons with timestamp and hides priorities`() = runBlocking {
        // Arrange
        val output = CatalogOutput()
        val tool = TodoTool(output, clock, ZoneId.of("Europe/Helsinki"))
        val args =
            Json.decodeFromString<TodoTool.Args>(
                """{"goal":"*Fix the bug* using <https://example.com|the spec>","todos":[
                {"content":"Inspect","status":"completed","priority":"high"},
                {"content":"Implement","status":"in_progress","priority":"high"},
                {"content":"Test","status":"pending","priority":"medium"},
                {"content":"Old plan","status":"cancelled","priority":"low"}
            ]}"""
            )

        // Act
        val result = tool.execute(args)

        // Assert
        assertEquals(args.todos, result)
        assertEquals(
            listOf(
                "todos" to
                    "*Fix the bug* using <https://example.com|the spec>\n\n" +
                        "✓ Inspect\n∗ Implement\n◦ Test\n× Old plan\n\n_todos as of 14:34:56 (just now)_"
            ),
            output.statusMessages,
        )
    }

    @Test
    fun `refresh ages the original update without changing its timestamp`() = runBlocking {
        // Arrange
        val output = CatalogOutput()
        val tool = TodoTool(output, clock, ZoneId.of("Europe/Helsinki"))
        tool.execute(
            TodoTool.Args(
                "Fix bug",
                listOf(TodoTool.Item("Test", TodoTool.Status.IN_PROGRESS, TodoTool.Priority.HIGH)),
            )
        )
        now = Instant.parse("2026-11-04T12:36:56Z")

        // Act
        val refreshed = output.statusRefreshes.getValue("todos").invoke()

        // Assert
        assertEquals("Fix bug\n\n∗ Test\n\n_todos as of 14:34:56 (2 minutes ago)_", refreshed)
    }

    @Test
    fun `scheduled publication reads the current goal tasks and update time`() = runBlocking {
        // Arrange
        val output = CatalogOutput()
        val tool = TodoTool(output, clock, ZoneId.of("Europe/Helsinki"))
        tool.execute(
            TodoTool.Args(
                "Old goal",
                listOf(TodoTool.Item("Old task", TodoTool.Status.PENDING, TodoTool.Priority.HIGH)),
            )
        )
        val scheduledPublication = output.statusRefreshes.getValue("todos")
        now = Instant.parse("2026-11-04T12:35:56Z")
        tool.execute(
            TodoTool.Args(
                "New goal",
                listOf(
                    TodoTool.Item("New task", TodoTool.Status.IN_PROGRESS, TodoTool.Priority.LOW)
                ),
            )
        )
        now = Instant.parse("2026-11-04T12:37:56Z")

        // Act
        val text = scheduledPublication()

        // Assert
        assertEquals("New goal\n\n∗ New task\n\n_todos as of 14:35:56 (2 minutes ago)_", text)
    }

    @Test
    fun `finished list removes the age and refresh callback`() = runBlocking {
        // Arrange
        val output = CatalogOutput()
        val tool = TodoTool(output, clock, ZoneId.of("Europe/Helsinki"))
        tool.execute(
            TodoTool.Args(
                "Fix bug",
                listOf(TodoTool.Item("Test", TodoTool.Status.PENDING, TodoTool.Priority.HIGH)),
            )
        )

        // Act
        tool.execute(
            TodoTool.Args(
                "Fix bug",
                listOf(
                    TodoTool.Item("Test", TodoTool.Status.COMPLETED, TodoTool.Priority.HIGH),
                    TodoTool.Item("Old plan", TodoTool.Status.CANCELLED, TodoTool.Priority.LOW),
                ),
            )
        )

        // Assert
        assertEquals(
            "todos" to "Fix bug\n\n✓ Test\n× Old plan\n\n_todos as of 14:34:56_",
            output.statusMessages.last(),
        )
        assertEquals(emptyMap(), output.statusRefreshes)
    }

    @Test
    fun `empty list replaces previous tasks with no tasks`() = runBlocking {
        // Arrange
        val output = CatalogOutput()
        val tool = TodoTool(output, clock, ZoneId.of("Europe/Helsinki"))
        tool.execute(
            TodoTool.Args(
                "Fix the bug",
                listOf(TodoTool.Item("Old task", TodoTool.Status.PENDING, TodoTool.Priority.LOW)),
            )
        )

        // Act
        tool.execute(TodoTool.Args("Fix the bug", emptyList()))

        // Assert
        assertEquals(
            "todos" to "Fix the bug\n\nNo tasks.\n\n_todos as of 14:34:56_",
            output.statusMessages.last(),
        )
    }

    @TestFactory
    fun `invalid todo arguments are rejected`() =
        listOf(
                "missing todos" to "{\"goal\":\"Fix the bug\"}",
                "missing goal" to "{\"todos\":[]}",
                "missing content" to
                    "{\"goal\":\"Fix\",\"todos\":[{\"status\":\"pending\",\"priority\":\"high\"}]}",
                "missing status" to
                    "{\"goal\":\"Fix\",\"todos\":[{\"content\":\"Test\",\"priority\":\"high\"}]}",
                "missing priority" to
                    "{\"goal\":\"Fix\",\"todos\":[{\"content\":\"Test\",\"status\":\"pending\"}]}",
                "unknown status" to
                    ("{\"goal\":\"Fix\",\"todos\":[{\"content\":\"Test\"," +
                        "\"status\":\"unknown\",\"priority\":\"high\"}]}"),
                "unknown priority" to
                    ("{\"goal\":\"Fix\",\"todos\":[{\"content\":\"Test\"," +
                        "\"status\":\"pending\",\"priority\":\"unknown\"}]}"),
            )
            .map { (name, json) ->
                dynamicTest(name) {
                    assertFailsWith<SerializationException> {
                        Json.decodeFromString<TodoTool.Args>(json)
                    }
                }
            }
}
