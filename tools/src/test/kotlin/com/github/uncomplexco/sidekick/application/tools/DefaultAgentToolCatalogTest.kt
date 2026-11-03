package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import com.github.uncomplexco.sidekick.application.agent.tools.ToolAccess
import com.github.uncomplexco.sidekick.application.agent.tools.ToolRegistration
import com.github.uncomplexco.sidekick.application.tools.mcp.McpServerConfig
import com.github.uncomplexco.sidekick.application.tools.mcp.McpToolsConfig
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import com.github.uncomplexco.sidekick.testing.CatalogChat
import com.github.uncomplexco.sidekick.testing.CatalogDiscordChat
import com.github.uncomplexco.sidekick.testing.CatalogMcpServer
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
import org.junit.jupiter.api.Test
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
                    "Glob",
                    "Grep",
                    "bash",
                    "git__clone",
                    "git__push",
                    "git__pull",
                    "webFetch",
                    "activateSkill",
                    "reloadSkills",
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
                    "assistant_report_status",
                    "Task",
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
        assertFalse("assistant_report_status" in registries[0].names())
        assertFalse("Task" in registries[0].names())
    }

    private fun ToolRegistry.names(): Set<String> = tools.map { it.name }.toSet()
}
