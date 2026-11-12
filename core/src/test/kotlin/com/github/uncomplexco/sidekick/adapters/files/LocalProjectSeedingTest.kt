package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalProjectSeedingTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `seeds supplied directory contents including nested files and empty directories`() =
        runBlocking {
            val source = Files.createDirectories(dir.resolve("supplied/nested"))
            Files.writeString(source.resolve("config.txt"), "configuration")
            Files.createDirectories(dir.resolve("supplied/empty"))
            Files.writeString(dir.resolve("supplied/AGENTS.md"), "instructions")
            val filesystem = filesystem()

            filesystem.seedProject("project", dir.resolve("supplied"))

            assertEquals("instructions", Files.readString(dir.resolve("project/AGENTS.md")))
            assertEquals(
                "configuration",
                Files.readString(dir.resolve("project/nested/config.txt")),
            )
            assertTrue(Files.isDirectory(dir.resolve("project/empty")))
            assertEquals("instructions", Files.readString(dir.resolve("supplied/AGENTS.md")))
        }

    @Test
    fun `preserves existing project files`() = runBlocking {
        val source = Files.createDirectories(dir.resolve("supplied"))
        Files.writeString(source.resolve("AGENTS.md"), "template instructions")
        val project = Files.createDirectories(dir.resolve("project"))
        Files.writeString(project.resolve("AGENTS.md"), "project instructions")
        val filesystem = filesystem()

        filesystem.seedProject("project", source)

        assertEquals("project instructions", Files.readString(project.resolve("AGENTS.md")))
    }

    @Test
    fun `repeated seeding adds missing files without replacing project edits`() = runBlocking {
        val source = Files.createDirectories(dir.resolve("supplied"))
        Files.writeString(source.resolve("AGENTS.md"), "template instructions")
        val filesystem = filesystem()
        filesystem.seedProject("project", source)
        Files.writeString(dir.resolve("project/AGENTS.md"), "edited instructions")
        Files.writeString(source.resolve("new.txt"), "new content")

        filesystem.seedProject("project", source)

        assertEquals("edited instructions", Files.readString(dir.resolve("project/AGENTS.md")))
        assertEquals("new content", Files.readString(dir.resolve("project/new.txt")))
    }

    private fun filesystem(): WorkspaceFilesystem =
        LocalWorkspaceFilesystem(
            VirtualPaths(
                sessionRoot = dir.resolve("session"),
                skillsRoot = dir.resolve("skills"),
                globalRoot = dir.resolve("global"),
                workRoot = dir.resolve("work"),
                projectRoot = dir.resolve("project"),
            )
        )
}
