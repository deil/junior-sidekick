package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalWorkspaceFilesystemWriteTextTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `write creates parent directories and preserves utf8 text`() = runBlocking {
        filesystem().writeText("C123", "/data/project/nested/file.txt", "héllo 😀\r\n")

        assertEquals("héllo 😀\r\n", Files.readString(dir.resolve("project/nested/file.txt")))
    }

    @Test
    fun `write overwrites and truncates an existing file`() = runBlocking {
        Files.createDirectories(dir.resolve("project"))
        Files.writeString(dir.resolve("project/file.txt"), "long original content")

        filesystem().writeText("C123", "/data/project/file.txt", "new")

        assertEquals("new", Files.readString(dir.resolve("project/file.txt")))
    }

    @Test
    fun `write accepts empty content`() = runBlocking {
        filesystem().writeText("C123", "/data/project/file.txt", "")

        assertEquals("", Files.readString(dir.resolve("project/file.txt")))
    }

    @Test
    fun `write resolves the session work root`() = runBlocking {
        filesystem().writeText("C123", "/work/file.txt", "work")

        assertEquals("work", Files.readString(dir.resolve("work/file.txt")))
    }

    @Test
    fun `write rejects a directory`() {
        Files.createDirectories(dir.resolve("project/folder"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().writeText("C123", "/data/project/folder", "content") }
        }
    }

    @Test
    fun `write rejects a symbolic link file without modifying its target`() {
        Files.createDirectories(dir.resolve("project"))
        Files.writeString(dir.resolve("project/target.txt"), "original")
        Files.createSymbolicLink(
            dir.resolve("project/linked.txt"),
            dir.resolve("project/target.txt"),
        )

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().writeText("C123", "/data/project/linked.txt", "changed") }
        }
        assertEquals("original", Files.readString(dir.resolve("project/target.txt")))
    }

    @Test
    fun `write rejects a symbolic link parent without creating a file`() {
        Files.createDirectories(dir.resolve("project/real"))
        Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("project/real"))

        assertThrows<IllegalArgumentException> {
            runBlocking {
                filesystem().writeText("C123", "/data/project/linked/new.txt", "content")
            }
        }
        assertFalse(Files.exists(dir.resolve("project/real/new.txt")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/unknown/file.txt", "relative.txt", "/data/project/../outside.txt"])
    fun `write rejects unsupported paths and root escapes`(path: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().writeText("C123", path, "content") }
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
