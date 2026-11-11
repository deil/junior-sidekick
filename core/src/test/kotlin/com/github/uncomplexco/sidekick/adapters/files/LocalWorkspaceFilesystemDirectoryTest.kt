package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryEntry
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPage
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPageOptions
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalWorkspaceFilesystemDirectoryTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `lists sorted immediate children with file kinds`() = runBlocking {
        Files.createDirectories(dir.resolve("project/b/nested"))
        Files.writeString(dir.resolve("project/z.txt"), "z")
        Files.writeString(dir.resolve("project/a.txt"), "a")

        val result = filesystem().listDirectory("C123", "/data/project/")

        assertEquals(
            DirectoryPage(
                listOf(
                    DirectoryEntry("a.txt", FileKind.FILE),
                    DirectoryEntry("b", FileKind.DIRECTORY),
                    DirectoryEntry("z.txt", FileKind.FILE),
                ),
                3,
                false,
            ),
            result,
        )
    }

    @Test
    fun `paginates entries with a one based offset`() = runBlocking {
        Files.createDirectories(dir.resolve("project/a"))
        Files.createDirectory(dir.resolve("project/b"))
        Files.createDirectory(dir.resolve("project/c"))

        val result = filesystem().listDirectory("C123", "/data/project", DirectoryPageOptions(2, 1))

        assertEquals(
            DirectoryPage(listOf(DirectoryEntry("b", FileKind.DIRECTORY)), 3, true),
            result,
        )
    }

    @Test
    fun `unbounded listing returns all entries after offset`() = runBlocking {
        Files.createDirectories(dir.resolve("project/a"))
        Files.createDirectory(dir.resolve("project/b"))

        val result = filesystem().listDirectory("C123", "/data/project", DirectoryPageOptions(2))

        assertEquals(
            DirectoryPage(listOf(DirectoryEntry("b", FileKind.DIRECTORY)), 2, false),
            result,
        )
    }

    @Test
    fun `offset beyond directory returns an empty page`() = runBlocking {
        Files.createDirectories(dir.resolve("project/a"))

        val result = filesystem().listDirectory("C123", "/data/project", DirectoryPageOptions(3, 1))

        assertEquals(DirectoryPage(emptyList(), 1, false), result)
    }

    @Test
    fun `empty directory returns an empty page`() = runBlocking {
        Files.createDirectories(dir.resolve("project"))

        val result = filesystem().listDirectory("C123", "/data/project")

        assertEquals(DirectoryPage(emptyList(), 0, false), result)
    }

    @Test
    fun `virtual root lists configured roots without requiring local directories`() = runBlocking {
        val result = filesystem().listDirectory("C123", "/")

        assertEquals(
            DirectoryPage(
                listOf(
                    DirectoryEntry("data", FileKind.DIRECTORY),
                    DirectoryEntry("work", FileKind.DIRECTORY),
                ),
                2,
                false,
            ),
            result,
        )
    }

    @Test
    fun `data root supports pagination`() = runBlocking {
        val result = filesystem().listDirectory("C123", "/data/", DirectoryPageOptions(2, 2))

        assertEquals(
            DirectoryPage(
                listOf(
                    DirectoryEntry("project", FileKind.DIRECTORY),
                    DirectoryEntry("session", FileKind.DIRECTORY),
                ),
                4,
                true,
            ),
            result,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["/unknown", "relative", "/data/project/../outside"])
    fun `listing rejects unsupported paths and root escapes`(path: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().listDirectory("C123", path) }
        }
    }

    @Test
    fun `listing rejects a symbolic link directory`() {
        Files.createDirectories(dir.resolve("project/real"))
        Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("project/real"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().listDirectory("C123", "/data/project/linked") }
        }
    }

    @Test
    fun `listing fails for a missing directory`() {
        assertThrows<NoSuchFileException> {
            runBlocking { filesystem().listDirectory("C123", "/data/project/missing") }
        }
    }

    @Test
    fun `listing fails for a file`() {
        Files.createDirectories(dir.resolve("project"))
        Files.writeString(dir.resolve("project/file.txt"), "content")

        assertThrows<NotDirectoryException> {
            runBlocking { filesystem().listDirectory("C123", "/data/project/file.txt") }
        }
    }

    private fun filesystem() =
        WorkspaceFilesystemFactory()
            .create(
                VirtualPaths(
                    sessionRoot = dir.resolve("session"),
                    skillsRoot = dir.resolve("skills"),
                    globalRoot = dir.resolve("global"),
                    workRoot = dir.resolve("work"),
                    projectRoot = dir.resolve("project"),
                )
            )
}
