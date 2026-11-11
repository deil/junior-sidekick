package com.github.uncomplexco.sidekick.application.tools.files

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.adapters.files.WorkspaceFilesystemFactory
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class WorkspaceFileToolsTest {
    @TempDir lateinit var dir: Path

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
    fun `read lists virtual root`() {
        val result = tools().workspaceFileRead("/")

        assertContains(result, "<path>/</path>")
        assertContains(result, "<type>directory</type>")
        assertContains(result, "data/")
        assertContains(result, "work/")
    }

    @Test
    fun `read lists data root`() {
        val result = tools().workspaceFileRead("/data/")

        assertContains(result, "<path>/data</path>")
        assertContains(result, "<type>directory</type>")
        assertContains(result, "global/")
        assertContains(result, "project/")
        assertContains(result, "session/")
        assertContains(result, "skills/")
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
    fun `read directory preserves pagination output`() {
        Files.createDirectories(dir.resolve("project/a"))
        Files.createDirectory(dir.resolve("project/b"))
        Files.createDirectory(dir.resolve("project/c"))

        val result = tools().workspaceFileRead("/data/project", offset = 2, limit = 1)

        assertContains(result, "<path>/data/project</path>")
        assertContains(result, "<type>directory</type>")
        assertContains(result, "<entries>\nb/\n")
        assertContains(
            result,
            "Showing 1 of 3 entries. Use 'offset' parameter to read beyond entry 3",
        )
    }

    @Test
    fun `glob searches data root`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.createDirectories(dir.resolve("project/src"))
        Files.writeString(dir.resolve("session/report.md"), "Report\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "Skill\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "Security\n")
        Files.writeString(dir.resolve("project/src/notes.md"), "Project\n")

        val result = tools().workspaceFileGlob("**/*.md", "/data")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
        assertContains(result, "/data/project/src/notes.md")
    }

    @Test
    fun `glob searches project root`() {
        Files.createDirectories(dir.resolve("project/src"))
        Files.writeString(dir.resolve("project/src/notes.md"), "Project\n")

        val result = tools().workspaceFileGlob("**/*.md", "/data/project")

        assertContains(result, "src/notes.md")
    }

    @Test
    fun `glob searches all known virtual roots`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.createDirectories(dir.resolve("work"))
        Files.createDirectories(dir.resolve("project/src"))
        Files.writeString(dir.resolve("session/report.md"), "Report\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "Skill\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "Security\n")
        Files.writeString(dir.resolve("work/result.md"), "Result\n")
        Files.writeString(dir.resolve("project/src/notes.md"), "Project\n")

        val result = tools().workspaceFileGlob("**/*.md", "/")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
        assertContains(result, "/work/result.md")
        assertContains(result, "/data/project/src/notes.md")
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
        assertContains(result, "/data/global/handbook/security.md:")
        assertContains(result, "Line 1: SOC2 evidence")
    }

    @Test
    fun `grep searches data root`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.createDirectories(dir.resolve("project/src"))
        Files.writeString(dir.resolve("session/report.md"), "needle in session\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "needle in skills\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "needle in global\n")
        Files.writeString(dir.resolve("project/src/notes.md"), "needle in project\n")

        val result = tools().workspaceFileGrep("needle", "/data", "**/*.md")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
        assertContains(result, "/data/project/src/notes.md")
    }

    @Test
    fun `grep clips matching lines in tool output`() {
        Files.createDirectories(dir.resolve("project"))
        Files.writeString(dir.resolve("project/long.txt"), "needle" + "x".repeat(3000))

        val result = tools().workspaceFileGrep("needle", "/data/project")

        assertContains(result, "Line 1: needle${"x".repeat(1994)}...")
    }

    @Test
    fun `grep preserves truncation output`() {
        Files.createDirectories(dir.resolve("project"))
        Files.writeString(dir.resolve("project/many.txt"), "needle\n".repeat(101))

        val result = tools().workspaceFileGrep("needle", "/data/project")

        assertContains(result, "Found 101 matches (showing first 100)")
        assertContains(result, "Results truncated: showing 100 of 101 matches (1 hidden)")
    }

    @Test
    fun `grep reports no matches`() {
        Files.createDirectories(dir.resolve("project"))
        Files.writeString(dir.resolve("project/hello.txt"), "hello")

        val result = tools().workspaceFileGrep("needle", "/data/project")

        assertEquals("No files found", result)
    }

    @Test
    fun `grep searches project root`() {
        Files.createDirectories(dir.resolve("project/src"))
        Files.writeString(dir.resolve("project/src/notes.md"), "needle in project\n")

        val result = tools().workspaceFileGrep("needle", "/data/project", "**/*.md")

        assertContains(result, "src/notes.md")
        assertContains(result, "Line 1: needle in project")
    }

    @Test
    fun `grep searches all known virtual roots`() {
        Files.createDirectories(dir.resolve("session"))
        Files.createDirectories(dir.resolve("skills/repo/skill"))
        Files.createDirectories(dir.resolve("global/handbook"))
        Files.createDirectories(dir.resolve("work"))
        Files.createDirectories(dir.resolve("project/src"))
        Files.writeString(dir.resolve("session/report.md"), "needle in session\n")
        Files.writeString(dir.resolve("skills/repo/skill/SKILL.md"), "needle in skills\n")
        Files.writeString(dir.resolve("global/handbook/security.md"), "needle in global\n")
        Files.writeString(dir.resolve("work/result.md"), "needle in work\n")
        Files.writeString(dir.resolve("project/src/notes.md"), "needle in project\n")

        val result = tools().workspaceFileGrep("needle", "/", "**/*.md")

        assertContains(result, "/data/session/report.md")
        assertContains(result, "/data/skills/repo/skill/SKILL.md")
        assertContains(result, "/data/global/handbook/security.md")
        assertContains(result, "/work/result.md")
        assertContains(result, "/data/project/src/notes.md")
    }

    @Test
    fun `unsupported virtual paths are reported as not found`() {
        for (path in unsupportedPaths()) {
            val error =
                assertThrows<ToolException.ValidationFailure> { tools().workspaceFileRead(path) }

            assertContains(error.message.orEmpty(), "Path not found: $path")
        }
    }

    private fun tools(): WorkspaceFileTools {
        val paths =
            VirtualPaths(
                sessionRoot = dir.resolve("session"),
                skillsRoot = dir.resolve("skills"),
                globalRoot = dir.resolve("global"),
                workRoot = dir.resolve("work"),
                projectRoot = dir.resolve("project"),
            )
        return WorkspaceFileTools(
            "C123",
            WorkspaceFilesystemFactory().create(paths),
        )
    }

    private fun readCases(): List<Pair<String, Path>> =
        listOf(
            "/data/session/file.md" to dir.resolve("session/file.md"),
            "/data/skills/repo/skill/SKILL.md" to dir.resolve("skills/repo/skill/SKILL.md"),
            "/data/global/handbook/security.md" to dir.resolve("global/handbook/security.md"),
            "/work/result.md" to dir.resolve("work/result.md"),
            "/data/project/src/notes.md" to dir.resolve("project/src/notes.md"),
        )

    private fun unsupportedPaths(): List<String> =
        listOf(
            "/unknown/file.md",
            "relative/file.md",
            "/data/sessions/file.md",
        )
}
