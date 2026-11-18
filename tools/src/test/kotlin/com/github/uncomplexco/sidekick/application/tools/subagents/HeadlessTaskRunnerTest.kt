package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.adapters.files.LocalWorkspaceFilesystem
import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import com.github.uncomplexco.sidekick.testing.EmptyTaskMcpRegistry
import com.github.uncomplexco.sidekick.testing.ToolCatalogFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HeadlessTaskRunnerTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `headless run uses the prefixed conversation and normal delegate tools`() = runBlocking {
        val fixture = ToolCatalogFixture(directory)
        val runner =
            HeadlessTaskRunner(
                fixture.config,
                fixture.catalog,
                fixture.runner,
                WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem),
                EmptyTaskMcpRegistry,
            )

        val result =
            runner.runIsolated(
                "api-task-example",
                Subagent("general", "General", "Answer the question"),
                "Find the answer",
            )

        assertEquals("Task completed", assertIs<AgentRunResult.Completed<String>>(result).output)
        assertEquals(
            ConversationId("api-task-example", "api-task-example"),
            fixture.runner.runIds.single().conversationId,
        )
        val toolNames = fixture.runner.registries.single().tools.map { it.name }.toSet()
        assertTrue(toolNames.containsAll(setOf("Read", "Task", "bash")))
        val workspace = directory.resolve("data/workspaces/tasks/api-task-example")
        assertTrue(Files.isDirectory(workspace.resolve("work")))
        assertTrue(Files.isDirectory(workspace.resolve("project")))
    }
}
