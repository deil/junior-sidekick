package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxCommand
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BashToolsTest {
    @Test
    fun `bash delegates command to sandbox executor with conversation scratch mount`() {
        // Arrange
        val scratch = Files.createTempDirectory("bash-tools-test")
        val config =
            BashToolConfig().apply {
                enabled = true
                networkEnabled = true
                timeout = 10
            }
        lateinit var captured: SandboxCommand
        val tools =
            BashTools(config, scratch) { command ->
                captured = command
                SandboxExecutionResult(
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
        assertEquals(1, captured.mounts.size)
        assertEquals(scratch, captured.mounts.single().source)
        assertEquals("/work", captured.mounts.single().target)
        assertEquals(SandboxMountMode.RW, captured.mounts.single().mode)
        assertTrue(Files.isDirectory(scratch))
    }

    @Test
    fun `bash maps sandbox validation errors to tool validation failures`() {
        // Arrange
        val scratch = Files.createTempDirectory("bash-tools-test")
        val config = BashToolConfig().apply { enabled = true }
        val tools =
            BashTools(config, scratch) {
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
}
