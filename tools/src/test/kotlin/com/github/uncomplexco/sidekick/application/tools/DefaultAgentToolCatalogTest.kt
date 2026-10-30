package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.JSONObject
import com.github.uncomplexco.sidekick.application.agent.tools.AgentToolRole
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

            val sidekick = fixture.catalog.bind(AgentToolRole.SIDEKICK, context).names()
            val task = fixture.catalog.bind(AgentToolRole.TASK, context).names()
            val loop = fixture.catalog.bind(AgentToolRole.LOOP, context).names()

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
                    "Loop",
                    "attachFile",
                    "slackCanvasCreate",
                    "slackReactionAdd",
                    "enableTokenmaxxin",
                    "scheduled_jobs__create",
                    "scheduled_jobs__list",
                    "scheduled_jobs__update",
                    "scheduled_jobs__delete",
                )
            assertTrue(task.containsAll(shared), "Missing shared tools: ${shared - task}")
            assertEquals(task, loop)
            assertTrue(
                sidekick.containsAll(task + orchestrator),
                "Missing Sidekick tools: ${orchestrator - sidekick}",
            )
            assertTrue((task intersect orchestrator).isEmpty())
        }

    @Test
    fun `disabled integrations are absent for every role`() = runBlocking {
        val fixture = ToolCatalogFixture(root, bashEnabled = false)
        for (role in AgentToolRole.entries) {
            val names = fixture.catalog.bind(role, fixture.execution(chat = CatalogChat())).names()
            assertFalse("bash" in names)
            assertFalse(names.any { it.startsWith("slack") || it.startsWith("discord") })
            assertFalse("attachFile" in names)
            assertFalse(names.any { it.startsWith("scheduled_jobs__") })
        }
    }

    @Test
    fun `discord reads are shared without leaking Slack tools`() = runBlocking {
        val fixture = ToolCatalogFixture(root)
        for (role in AgentToolRole.entries) {
            val names =
                fixture.catalog.bind(role, fixture.execution(chat = CatalogDiscordChat())).names()
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

        val firstTools = fixture.catalog.bind(AgentToolRole.TASK, first)
        val secondTools = fixture.catalog.bind(AgentToolRole.LOOP, second)

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
        fixture.catalog.bind(AgentToolRole.SIDEKICK, first)
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

            val task = fixture.catalog.bind(AgentToolRole.TASK, first)
            val loop = fixture.catalog.bind(AgentToolRole.LOOP, second)
            val sidekick = fixture.catalog.bind(AgentToolRole.SIDEKICK, first)

            assertContains(sidekick.names(), "mcpMessage")
            assertFalse("mcpMessage" in task.names())
            assertFalse("mcpMessage" in loop.names())
            assertNotSame(task.getTool("mcpRead"), loop.getTool("mcpRead"))
            assertEquals(
                first.turn.conversation.virtualPaths.workRoot.toString(),
                task.getTool<ContextProbeTool>().execute(JSONObject(emptyMap())),
            )
            assertEquals(
                second.turn.conversation.virtualPaths.workRoot.toString(),
                loop.getTool<ContextProbeTool>().execute(JSONObject(emptyMap())),
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
            for (role in AgentToolRole.entries) {
                val names = fixture.catalog.bind(role, fixture.execution()).names()
                assertContains(names, "get_mcp_status_test")
                assertEquals(role == AgentToolRole.SIDEKICK, "connect_mcp_test" in names)
            }
        }

    @Test
    fun `Task starts a separately bound execution with fixed shared access`() = runBlocking {
        val fixture = ToolCatalogFixture(root)
        val parent = fixture.execution()
        val registry = fixture.catalog.bind(AgentToolRole.SIDEKICK, parent)
        val task = registry.getTool<TaskTool>()

        assertEquals(
            "Task completed",
            task.execute(TaskTool.Args("Inspect repository", "Inspect the project")),
        )
        task.execute(TaskTool.Args("Inspect again", "Inspect again"))

        val contexts = fixture.runner.contexts
        val registries = fixture.runner.registries
        assertEquals(2, contexts.size)
        assertNotEquals(parent.turn.turnId, contexts[0].turnId)
        assertNotEquals(contexts[0].turnId, contexts[1].turnId)
        assertNotSame(parent.turn, contexts[0])
        assertEquals(parent.turn.conversation.virtualPaths, contexts[0].conversation.virtualPaths)
        assertNotSame(registry.getTool("Read"), registries[0].getTool("Read"))
        assertNotSame(registries[0].getTool("Read"), registries[1].getTool("Read"))
        assertContains(registries[0].names(), "bash")
        assertFalse("assistant_report_status" in registries[0].names())
        assertFalse("Task" in registries[0].names())
    }

    private fun ToolRegistry.names(): Set<String> = tools.map { it.name }.toSet()
}
