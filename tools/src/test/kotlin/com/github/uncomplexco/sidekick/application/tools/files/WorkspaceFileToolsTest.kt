package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains

class WorkspaceFileToolsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads global file through virtual path`() {
        Files.createDirectories(dir.resolve("handbook"))
        Files.writeString(dir.resolve("handbook/security.md"), "Keep secrets secret.\n")

        val result = WorkspaceFileTools(dir).workspaceFileRead("global:/handbook/security.md")

        assertContains(result, "<path>global:/handbook/security.md</path>")
        assertContains(result, "1: Keep secrets secret.")
    }

    @Test
    fun `glob returns workspace relative paths`() {
        Files.createDirectories(dir.resolve("handbook"))
        Files.writeString(dir.resolve("handbook/security.md"), "Security\n")
        Files.writeString(dir.resolve("handbook/benefits.md"), "Benefits\n")

        val result = WorkspaceFileTools(dir).workspaceFileGlob("**/*.md", "global:/")

        assertContains(result, "handbook/security.md")
        assertContains(result, "handbook/benefits.md")
    }

    @Test
    fun `grep returns native workspace file matches`() {
        Files.createDirectories(dir.resolve("handbook"))
        Files.writeString(dir.resolve("handbook/security.md"), "SOC2 evidence\n")

        val result = WorkspaceFileTools(dir).workspaceFileGrep("SOC2", "global:/", "**/*.md")

        assertContains(result, "Found 1 matches")
        assertContains(result, "${dir.resolve("handbook/security.md")}:")
        assertContains(result, "Line 1: SOC2 evidence")
    }

    @Test
    fun `rejects non global paths`() {
        val error =
            assertThrows<ToolException.ValidationFailure> {
                WorkspaceFileTools(dir).workspaceFileRead("session:/attachments/file.md")
            }

        assertContains(error.message.orEmpty(), "Only global:/ workspace paths are currently supported.")
    }
}
