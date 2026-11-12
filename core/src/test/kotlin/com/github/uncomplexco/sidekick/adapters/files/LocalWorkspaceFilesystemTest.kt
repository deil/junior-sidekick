package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import com.github.uncomplexco.sidekick.ports.filesystem.FileMetadata
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalWorkspaceFilesystemTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest
    @ValueSource(
        strings = ["/data/session", "/data/skills", "/data/global", "/work", "/data/project"]
    )
    fun `stat resolves virtual roots and returns file metadata`(root: String) = runBlocking {
        val modifiedAt = Instant.parse("2026-01-01T00:00:00Z")
        val file = Files.writeString(dir.resolve("file.txt"), "hello")
        Files.setLastModifiedTime(file, FileTime.from(modifiedAt))

        val result = filesystem().stat("C123", "$root/file.txt")

        assertEquals(FileMetadata(FileKind.FILE, 5, modifiedAt), result)
    }

    @Test
    fun `stat identifies a directory with a trailing slash`() = runBlocking {
        Files.createDirectory(dir.resolve("folder"))

        val result = filesystem().stat("C123", "/data/project/folder/")

        assertEquals(FileKind.DIRECTORY, result?.kind)
    }

    @Test
    fun `stat returns null for a missing file`() = runBlocking {
        val result = filesystem().stat("C123", "/data/project/missing.txt")

        assertNull(result)
    }

    @Test
    fun `stat returns null for a missing workspace root`() = runBlocking {
        val paths = paths().copy(projectRoot = dir.resolve("missing"))

        val result =
            WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem)
                .create(paths)
                .stat("C123", "/data/project")

        assertNull(result)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/unknown/file.txt", "relative.txt", "/data/project/../outside.txt"])
    fun `stat rejects unsupported paths and root escapes`(path: String) {
        assertThrows<IllegalArgumentException> { runBlocking { filesystem().stat("C123", path) } }
    }

    @Test
    fun `stat rejects a symbolic link file`() {
        Files.writeString(dir.resolve("target.txt"), "hello")
        Files.createSymbolicLink(dir.resolve("linked.txt"), dir.resolve("target.txt"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().stat("C123", "/data/project/linked.txt") }
        }
    }

    @Test
    fun `stat rejects a symbolic link parent for a missing file`() {
        Files.createDirectory(dir.resolve("target"))
        Files.createSymbolicLink(dir.resolve("linked"), dir.resolve("target"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().stat("C123", "/data/project/linked/missing.txt") }
        }
    }

    private fun filesystem() =
        WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem).create(paths())

    private fun paths() =
        VirtualPaths(
            sessionRoot = dir,
            skillsRoot = dir,
            globalRoot = dir,
            workRoot = dir,
            projectRoot = dir,
        )
}
