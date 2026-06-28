package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.ExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BashToolsTest {
    @Test
    fun `bash delegates command to sandbox executor with conversation work mount`() {
        // Arrange
        val dir = Files.createTempDirectory("bash-tools-test")
        val sessionRoot = dir.resolve("session")
        val skillsRoot = dir.resolve("skills")
        val globalRoot = dir.resolve("global")
        val workRoot = dir.resolve("work")
        val projectRoot = dir.resolve("project")
        Files.createDirectories(projectRoot)
        val config =
            BashToolConfig().apply {
                enabled = true
                networkEnabled = true
                timeout = 10
            }
        lateinit var captured: Command
        val tools =
            BashTools(config, VirtualPaths(sessionRoot, skillsRoot, globalRoot, workRoot, projectRoot)) { command ->
                captured = command
                ExecutionResult(
                    ok = true,
                    exitCode = 0,
                    timedOut = false,
                    outputTruncated = false,
                    output = "ok",
                    workdir = command.workdir,
                )
            }

        // Act
        val result = tools.bash(command = "pwd", description = "prints working directory", workdir = "tmp", timeout = 20)

        // Assert
        assertEquals(true, result.ok)
        assertEquals("ok", result.output)
        assertEquals("pwd", captured.command)
        assertEquals("tmp", captured.workdir)
        assertEquals(10, captured.timeoutSeconds)
        assertEquals(true, captured.networkEnabled)
        assertEquals(5, captured.mounts.size)
        assertEquals(sessionRoot, captured.mounts[0].source)
        assertEquals("/data/session", captured.mounts[0].target)
        assertEquals(SandboxMountMode.RO, captured.mounts[0].mode)
        assertEquals(skillsRoot, captured.mounts[1].source)
        assertEquals("/data/skills", captured.mounts[1].target)
        assertEquals(SandboxMountMode.RO, captured.mounts[1].mode)
        assertEquals(globalRoot, captured.mounts[2].source)
        assertEquals("/data/global", captured.mounts[2].target)
        assertEquals(SandboxMountMode.RO, captured.mounts[2].mode)
        assertEquals(workRoot, captured.mounts[3].source)
        assertEquals("/work", captured.mounts[3].target)
        assertEquals(SandboxMountMode.RW, captured.mounts[3].mode)
        assertEquals(projectRoot, captured.mounts[4].source)
        assertEquals("/data/project", captured.mounts[4].target)
        assertEquals(SandboxMountMode.RW, captured.mounts[4].mode)
        assertTrue(Files.isDirectory(sessionRoot))
        assertTrue(Files.isDirectory(skillsRoot))
        assertTrue(Files.isDirectory(globalRoot))
        assertTrue(Files.isDirectory(workRoot))
        assertTrue(Files.isDirectory(projectRoot))
    }

    @Test
    fun `bash maps sandbox validation errors to tool validation failures`() {
        // Arrange
        val scratch = Files.createTempDirectory("bash-tools-test")
        val config = BashToolConfig().apply { enabled = true }
        val tools =
            BashTools(config, virtualPaths(scratch)) {
                throw IllegalArgumentException("mount target must be absolute")
            }

        // Act
        val error =
            assertFailsWith<ToolException.ValidationFailure> {
                tools.bash(command = "pwd", description = "prints working directory")
            }

        // Assert
        assertEquals("mount target must be absolute", error.message)
    }

    @Test
    fun `bash applies configured scratch gid and group-writable setgid permissions to writable mounts`() {
        // Arrange
        val scratch = Files.createTempDirectory("bash-tools-test")
        val workRoot = scratch.resolve("work")
        val projectRoot = scratch.resolve("project")
        val gid = Files.getAttribute(scratch, "unix:gid") as Int
        val config =
            BashToolConfig().apply {
                enabled = true
                scratchGid = gid
            }
        val tools =
            BashTools(config, VirtualPaths(scratch.resolve("session"), scratch.resolve("skills"), scratch.resolve("global"), workRoot, projectRoot)) { command ->
                ExecutionResult(
                    ok = true,
                    exitCode = 0,
                    timedOut = false,
                    outputTruncated = false,
                    output = "ok",
                    workdir = command.workdir,
                )
            }

        // Act
        tools.bash(command = "pwd", description = "prints working directory")

        // Assert
        assertWritableMountMode(workRoot, gid)
        assertWritableMountMode(projectRoot, gid)
    }

    private fun assertWritableMountMode(
        path: java.nio.file.Path,
        gid: Int,
    ) {
        assertEquals(gid, Files.getAttribute(path, "unix:gid"))
        val mode = (Files.getAttribute(path, "unix:mode") as Int) and 0b111111111111
        assertEquals(0b010111111000, mode)
    }

    private fun virtualPaths(root: java.nio.file.Path): VirtualPaths =
        VirtualPaths(
            sessionRoot = root.resolve("session"),
            skillsRoot = root.resolve("skills"),
            globalRoot = root.resolve("global"),
            workRoot = root.resolve("work"),
            projectRoot = root.resolve("project"),
        )
}
