package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
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
    fun `reads files through virtual paths`() {
        for ((virtualPath, realPath) in readCases()) {
            Files.createDirectories(realPath.parent)
            Files.writeString(realPath, "Keep secrets secret.\n")

            val result = tools().workspaceFileRead(virtualPath)

            assertContains(result, "<path>$virtualPath</path>")
            assertContains(result, "1: Keep secrets secret.")
        }
    }

    @Test
    fun `glob returns workspace relative paths`() {
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.writeString(dir.resolve("global/handbook/security.md"), "Security\n")
        Files.writeString(dir.resolve("global/handbook/benefits.md"), "Benefits\n")

        val result = tools().workspaceFileGlob("**/*.md", "/data/global")

        assertContains(result, "handbook/security.md")
        assertContains(result, "handbook/benefits.md")
    }

    @Test
    fun `grep returns native workspace file matches`() {
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.writeString(dir.resolve("global/handbook/security.md"), "SOC2 evidence\n")

        val result = tools().workspaceFileGrep("SOC2", "/data/global", "**/*.md")

        assertContains(result, "Found 1 matches")
        assertContains(result, "${dir.resolve("global/handbook/security.md")}:")
        assertContains(result, "Line 1: SOC2 evidence")
    }

    @Test
    fun `unsupported virtual paths are reported as not found`() {
        for (path in unsupportedPaths()) {
            val error =
                assertThrows<ToolException.ValidationFailure> {
                    tools().workspaceFileRead(path)
                }

            assertContains(error.message.orEmpty(), "Path not found: $path")
        }
    }

    private fun tools(): WorkspaceFileTools =
        WorkspaceFileTools(
            VirtualPaths(
                sessionRoot = dir.resolve("session"),
                skillsRoot = dir.resolve("skills"),
                globalRoot = dir.resolve("global"),
                workRoot = dir.resolve("work"),
            ),
        )

    private fun readCases(): List<Pair<String, Path>> =
        listOf(
            "/data/session/file.md" to dir.resolve("session/file.md"),
            "/data/skills/repo/skill/SKILL.md" to dir.resolve("skills/repo/skill/SKILL.md"),
            "/data/global/handbook/security.md" to dir.resolve("global/handbook/security.md"),
            "/work/result.md" to dir.resolve("work/result.md"),
        )

    private fun unsupportedPaths(): List<String> =
        listOf(
            "/unknown/file.md",
            "relative/file.md",
            "/data/sessions/file.md",
        )
}
