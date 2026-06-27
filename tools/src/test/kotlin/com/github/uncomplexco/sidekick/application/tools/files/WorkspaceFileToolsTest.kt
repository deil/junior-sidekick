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
    fun `glob searches data root`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.writeString(dir.resolve("session/report.md"), "Report\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "Skill\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "Security\n")

        val result = tools().workspaceFileGlob("**/*.md", "/data")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
    }

    @Test
    fun `glob searches all known virtual roots`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.createDirectories(dir.resolve("work"))
        Files.writeString(dir.resolve("session/report.md"), "Report\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "Skill\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "Security\n")
        Files.writeString(dir.resolve("work/result.md"), "Result\n")

        val result = tools().workspaceFileGlob("**/*.md", "/")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
        assertContains(result, "/work/result.md")
    }

    @Test
    fun `glob from aggregate root preserves truncation notice`() {
        Files.createDirectories(dir.resolve("global/many"))
        repeat(101) { index ->
            Files.writeString(dir.resolve("global/many/file-$index.md"), "File $index\n")
        }

        val result = tools().workspaceFileGlob("**/*.md", "/data")

        assertContains(result, "/data/global/many/file-")
        assertContains(result, "Results are truncated")
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
    fun `grep searches data root`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.writeString(dir.resolve("session/report.md"), "needle in session\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "needle in skills\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "needle in global\n")

        val result = tools().workspaceFileGrep("needle", "/data", "**/*.md")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
    }

    @Test
    fun `grep searches all known virtual roots`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.createDirectories(dir.resolve("work"))
        Files.writeString(dir.resolve("session/report.md"), "needle in session\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "needle in skills\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "needle in global\n")
        Files.writeString(dir.resolve("work/result.md"), "needle in work\n")

        val result = tools().workspaceFileGrep("needle", "/", "**/*.md")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
        assertContains(result, "/work/result.md")
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
