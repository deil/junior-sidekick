package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import com.github.uncomplexco.sidekick.application.agent.tools.ToolAccess
import com.github.uncomplexco.sidekick.application.agent.tools.ToolRegistration
import com.github.uncomplexco.sidekick.application.tools.mcp.McpServerConfig
import com.github.uncomplexco.sidekick.application.tools.mcp.McpToolsConfig
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import com.github.uncomplexco.sidekick.application.tools.todos.TodoTool
import com.github.uncomplexco.sidekick.testing.CatalogChat
import com.github.uncomplexco.sidekick.testing.CatalogDiscordChat
import com.github.uncomplexco.sidekick.testing.CatalogMcpServer
import com.github.uncomplexco.sidekick.testing.CatalogSlackChat
import com.github.uncomplexco.sidekick.testing.ContextProbeTool
import com.github.uncomplexco.sidekick.testing.ToolCatalogFixture
import com.github.uncomplexco.sidekick.tools.SidekickToolContext
import com.github.uncomplexco.sidekick.tools.SidekickToolProvider
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

class DefaultAgentToolCatalogTest {
    @TempDir lateinit var root: Path

    @Test
    fun `delegates get development tools and communication reads without requester-facing tools`() =
        runBlocking {
            val fixture = ToolCatalogFixture(root)
            val context = fixture.execution()

            val sidekick = fixture.catalog.bindOrchestrator(context).names()
            val delegate = fixture.catalog.bindDelegate(context).names()

            val shared =
                setOf(
                    "Read",
                    "Todo",
                    "Task",
                    "Glob",
                    "Grep",
                    "bash",
                    "git__clone",
                    "git__push",
                    "git__pull",
                    "webFetch",
                    "activateSkill",
                    "currentDateTime",
                    "timestampToIsoUtc",
                    "slackChannelHistory",
                    "slackThreadHistory",
                    "slackChannelsList",
                    "slackUserSearch",
                    "slackFileRead",
                )
            val orchestrator =
                setOf(
                    "reloadSkills",
                    "attachFile",
                    "slackCanvasCreate",
                    "slackReactionAdd",
                    "enableTokenmaxxin",
                    "scheduled_jobs__create",
                    "scheduled_jobs__list",
                    "scheduled_jobs__update",
                    "scheduled_jobs__delete",
                )
            assertTrue(delegate.containsAll(shared), "Missing shared tools: ${shared - delegate}")
            assertTrue(
                sidekick.containsAll(delegate + orchestrator),
                "Missing Sidekick tools: ${orchestrator - sidekick}",
            )
            assertTrue((delegate intersect orchestrator).isEmpty())
            assertFalse("Loop" in sidekick)
            assertFalse("assistant_report_status" in sidekick)
        }

    @Test
    fun `orchestrator Todo publishes the supplied list`() = runBlocking {
        // Arrange
        val fixture = ToolCatalogFixture(root)
        val chat = CatalogChat()
        val registry = fixture.catalog.bindOrchestrator(fixture.execution(chat = chat))
        val todo = registry.getTool<TodoTool>()
        val todos =
            listOf(TodoTool.Item("Fix bug", TodoTool.Status.COMPLETED, TodoTool.Priority.HIGH))

        // Act
        val result = todo.execute(TodoTool.Args("Fix the bug", todos))

        // Assert
        assertEquals(todos, result)
        assertEquals("todos", chat.output.statusMessages.single().first)
        assertTrue(
            chat.output.statusMessages
                .single()
                .second
                .startsWith("Fix the bug\n\n✓ Fix bug\n\n_todos as of ")
        )
    }

    @Test
    fun `delegate Todo returns success without publishing a message`() = runBlocking {
        // Arrange
        val fixture = ToolCatalogFixture(root)
        val chat = CatalogChat()
        val registry = fixture.catalog.bindDelegate(fixture.execution(chat = chat))
        val todo = registry.getTool<TodoTool>()
        val todos =
            listOf(TodoTool.Item("Fix bug", TodoTool.Status.COMPLETED, TodoTool.Priority.HIGH))

        // Act
        val result = todo.execute(TodoTool.Args("Fix the bug", todos))

        // Assert
        assertEquals(todos, result)
        assertTrue(chat.output.statusMessages.isEmpty())
        assertTrue(chat.output.progress.isEmpty())
    }

    @TestFactory
    fun `headless Todo stays private on each chat platform`() =
        listOf("Slack" to CatalogSlackChat(), "Discord" to CatalogDiscordChat()).map { (name, chat)
            ->
            dynamicTest("$name headless Todo returns updates without publishing or refreshing") {
                runBlocking {
                    // Arrange
                    val fixture = ToolCatalogFixture(root)
                    val context = fixture.execution(chat = chat, headless = true)
                    val todo = fixture.catalog.bindOrchestrator(context).getTool<TodoTool>()
                    todo.execute(
                        TodoTool.Args(
                            "Run report",
                            listOf(
                                TodoTool.Item(
                                    "Collect data",
                                    TodoTool.Status.IN_PROGRESS,
                                    TodoTool.Priority.HIGH,
                                )
                            ),
                        )
                    )
                    val completed =
                        listOf(
                            TodoTool.Item(
                                "Collect data",
                                TodoTool.Status.COMPLETED,
                                TodoTool.Priority.HIGH,
                            )
                        )

                    // Act
                    val result = todo.execute(TodoTool.Args("Run report", completed))

                    // Assert
                    assertEquals(completed, result)
                    assertTrue(chat.output.statusMessages.isEmpty())
                    assertTrue(chat.output.statusRefreshes.isEmpty())
                    assertTrue(chat.output.progress.isEmpty())
                }
            }
        }

    @Test
    fun `disabled integrations are absent for orchestrators and delegates`() = runBlocking {
        val fixture = ToolCatalogFixture(root, bashEnabled = false)
        for (bind in listOf(fixture.catalog::bindOrchestrator, fixture.catalog::bindDelegate)) {
            val names = bind(fixture.execution(chat = CatalogChat())).names()
            assertFalse("bash" in names)
            assertFalse(names.any { it.startsWith("slack") || it.startsWith("discord") })
            assertFalse("attachFile" in names)
            assertFalse(names.any { it.startsWith("scheduled_jobs__") })
        }
    }

    @Test
    fun `discord reads are shared without leaking Slack tools`() = runBlocking {
        val fixture = ToolCatalogFixture(root)
        for (bind in listOf(fixture.catalog::bindOrchestrator, fixture.catalog::bindDelegate)) {
            val names = bind(fixture.execution(chat = CatalogDiscordChat())).names()
            assertContains(names, "discordChannelHistory")
            assertContains(names, "discordThreadHistory")
            assertFalse(names.any { it.startsWith("slack") })
        }
    }

    @Test
    fun `extensions are selected before binding and use each execution workspace`() = runBlocking {
        var publicationBindings = 0
        val provider = SidekickToolProvider {
            listOf(
                ToolRegistration<SidekickToolContext>("extension-read", ToolAccess.SHARED) { context
                    ->
                    listOf(
                        ContextProbeTool(
                            "extensionRead",
                            context.resolvePath("/work/output.txt").toString(),
                        )
                    )
                },
                ToolRegistration("extension-publish", ToolAccess.ORCHESTRATOR_ONLY) {
                    publicationBindings++
                    listOf(ContextProbeTool("extensionPublish", "published"))
                },
            )
        }
        val fixture = ToolCatalogFixture(root, providers = listOf(provider))
        val first = fixture.execution("first")
        val second = fixture.execution("second")

        val firstTools = fixture.catalog.bindDelegate(first)
        val secondTools = fixture.catalog.bindDelegate(second)

        assertEquals(0, publicationBindings)
        assertEquals(
            first.turn.conversation.virtualPaths.workRoot.resolve("output.txt").toString(),
            firstTools.getTool<ContextProbeTool>().execute(JSONObject(emptyMap())),
        )
        assertEquals(
            second.turn.conversation.virtualPaths.workRoot.resolve("output.txt").toString(),
            secondTools.getTool<ContextProbeTool>().execute(JSONObject(emptyMap())),
        )
        assertNotSame(firstTools.getTool("Read"), secondTools.getTool("Read"))
        fixture.catalog.bindOrchestrator(first)
        assertEquals(1, publicationBindings)
    }

    @Test
    fun `MCP sources follow access policy and bind fresh wrappers to current work root`() =
        runBlocking {
            val source =
                CatalogMcpServer(
                    listOf(
                        ToolRegistration("mcp-read", ToolAccess.SHARED) { path ->
                            listOf(ContextProbeTool("mcpRead", path.toString()))
                        },
                        ToolRegistration("mcp-message", ToolAccess.ORCHESTRATOR_ONLY) {
                            listOf(ContextProbeTool("mcpMessage", "sent"))
                        },
                    )
                )
            val fixture = ToolCatalogFixture(root)
            val first = fixture.execution("first", servers = listOf(source))
            val second = fixture.execution("second", servers = listOf(source))

            val task = fixture.catalog.bindDelegate(first)
            val secondDelegate = fixture.catalog.bindDelegate(second)
            val sidekick = fixture.catalog.bindOrchestrator(first)

            assertContains(sidekick.names(), "mcpMessage")
            assertFalse("mcpMessage" in task.names())
            assertFalse("mcpMessage" in secondDelegate.names())
            assertNotSame(task.getTool("mcpRead"), secondDelegate.getTool("mcpRead"))
            assertEquals(
                first.turn.conversation.virtualPaths.workRoot.toString(),
                task.getTool<ContextProbeTool>().execute(JSONObject(emptyMap())),
            )
            assertEquals(
                second.turn.conversation.virtualPaths.workRoot.toString(),
                secondDelegate.getTool<ContextProbeTool>().execute(JSONObject(emptyMap())),
            )
        }

    @Test
    fun `MCP configuration defaults to orchestrator-only with server and individual overrides`() {
        val server = McpServerConfig()
        assertEquals(ToolAccess.ORCHESTRATOR_ONLY, server.accessFor("build"))
        server.defaultToolAccess = ToolAccess.SHARED
        server.toolAccess = mapOf("sendMessage" to ToolAccess.ORCHESTRATOR_ONLY)
        assertEquals(ToolAccess.SHARED, server.accessFor("build"))
        assertEquals(ToolAccess.ORCHESTRATOR_ONLY, server.accessFor("sendMessage"))
        server.defaultToolAccess = ToolAccess.ORCHESTRATOR_ONLY
        server.toolAccess = mapOf("readHistory" to ToolAccess.SHARED)
        assertEquals(ToolAccess.SHARED, server.accessFor("readHistory"))
    }

    @Test
    fun `interactive MCP authentication is orchestrator-only while status is shared`() =
        runBlocking {
            val mcp = McpToolsConfig().apply { servers = listOf(McpServerConfig(id = "test")) }
            val fixture = ToolCatalogFixture(root, mcp = mcp)
            val orchestrator = fixture.catalog.bindOrchestrator(fixture.execution()).names()
            val delegate = fixture.catalog.bindDelegate(fixture.execution()).names()

            assertContains(orchestrator, "get_mcp_status_test")
            assertContains(orchestrator, "connect_mcp_test")
            assertContains(delegate, "get_mcp_status_test")
            assertFalse("connect_mcp_test" in delegate)
        }

    @Test
    fun `Task starts a separately bound execution with fixed shared access`() = runBlocking {
        val fixture = ToolCatalogFixture(root)
        val parent = fixture.execution()
        val registry = fixture.catalog.bindOrchestrator(parent)
        val task = registry.getTool<TaskTool>()

        assertEquals(
            "Task completed",
            task.execute(TaskTool.Args("Inspect repository", "Inspect the project")),
        )
        task.execute(TaskTool.Args("Inspect again", "Inspect again"))

        val runIds = fixture.runner.runIds
        val registries = fixture.runner.registries
        assertEquals(2, runIds.size)
        assertNotEquals(runIds[0].runId, runIds[1].runId)
        assertEquals(parent.turn.conversation.conversationId, runIds[0].conversationId)
        assertNotSame(registry.getTool("Read"), registries[0].getTool("Read"))
        assertNotSame(registries[0].getTool("Read"), registries[1].getTool("Read"))
        assertContains(registries[0].names(), "bash")
        assertContains(registries[0].names(), "Todo")
        assertFalse("assistant_report_status" in registries[0].names())
        assertContains(registries[0].names(), "Task")
    }

    @Test
    fun `subagents can delegate but their children cannot delegate further`() = runBlocking {
        // Arrange
        val fixture = ToolCatalogFixture(root)
        val parent = fixture.execution()
        val task = fixture.catalog.bindOrchestrator(parent).getTool<TaskTool>()
        task.execute(TaskTool.Args("Inspect repository", "Inspect the project"))
        val childTools = fixture.runner.registries.single()
        val childTask = childTools.getTool<TaskTool>()

        // Act
        val result = childTask.execute(TaskTool.Args("Check tests", "Inspect the tests"))

        // Assert
        assertEquals("Task completed", result)
        val grandchildTools = fixture.runner.registries.last()
        assertEquals(2, fixture.runner.runIds.size)
        assertFalse("Task" in grandchildTools.names())
        assertContains(grandchildTools.names(), "Read")
        assertContains(grandchildTools.names(), "bash")
        assertFalse("attachFile" in grandchildTools.names())
        assertNotSame(childTools.getTool("Read"), grandchildTools.getTool("Read"))
    }

    private fun ToolRegistry.names(): Set<String> = tools.map { it.name }.toSet()
}
