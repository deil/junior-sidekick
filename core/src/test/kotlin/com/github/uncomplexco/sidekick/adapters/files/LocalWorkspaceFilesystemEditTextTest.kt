package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
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

class LocalWorkspaceFilesystemEditTextTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `edit replaces a unique literal match and preserves surrounding text`() = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "before\r\nold.* text\r\nafter 😀\r\n")

        filesystem().editText("C123", "/data/project/file.txt", "old.* text", "new text")

        assertEquals(
            "before\r\nnew text\r\nafter 😀\r\n",
            Files.readString(dir.resolve("file.txt")),
        )
    }

    @Test
    fun `edit replaces all occurrences when requested`() = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "old old")

        filesystem().editText("C123", "/data/project/file.txt", "old", "new", replaceAll = true)

        assertEquals("new new", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `edit can remove matched text`() = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "before old after")

        filesystem().editText("C123", "/data/project/file.txt", "old ", "")

        assertEquals("before after", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `empty old string creates a file and parent directories`() = runBlocking {
        filesystem().editText("C123", "/data/project/nested/file.txt", "", "created")

        assertEquals("created", Files.readString(dir.resolve("nested/file.txt")))
    }

    @Test
    fun `empty old string overwrites an existing file`() = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "long original content")

        filesystem().editText("C123", "/data/project/file.txt", "", "new")

        assertEquals("new", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `edit rejects ambiguous matches without changing the file`() {
        Files.writeString(dir.resolve("file.txt"), "old old")

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().editText("C123", "/data/project/file.txt", "old", "new") }
        }
        assertEquals("old old", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `edit rejects missing text without changing the file`() {
        Files.writeString(dir.resolve("file.txt"), "original")

        assertThrows<IllegalArgumentException> {
            runBlocking {
                filesystem().editText("C123", "/data/project/file.txt", "missing", "new")
            }
        }
        assertEquals("original", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `edit rejects identical replacement text without changing the file`() {
        Files.writeString(dir.resolve("file.txt"), "same")

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().editText("C123", "/data/project/file.txt", "same", "same") }
        }
        assertEquals("same", Files.readString(dir.resolve("file.txt")))
    }

    @Test
    fun `edit rejects a missing file instead of creating it for nonempty old text`() {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().editText("C123", "/data/project/missing.txt", "old", "new") }
        }
        assertFalse(Files.exists(dir.resolve("missing.txt")))
    }

    @Test
    fun `edit rejects a directory`() {
        Files.createDirectory(dir.resolve("folder"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().editText("C123", "/data/project/folder", "", "new") }
        }
    }

    @Test
    fun `edit rejects symbolic links without changing the target`() {
        Files.writeString(dir.resolve("target.txt"), "original")
        Files.createSymbolicLink(dir.resolve("linked.txt"), dir.resolve("target.txt"))

        assertThrows<IllegalArgumentException> {
            runBlocking {
                filesystem().editText("C123", "/data/project/linked.txt", "original", "new")
            }
        }
        assertEquals("original", Files.readString(dir.resolve("target.txt")))
    }

    @Test
    fun `edit rejects creation through a symbolic link parent`() {
        Files.createDirectory(dir.resolve("real"))
        Files.createSymbolicLink(dir.resolve("linked"), dir.resolve("real"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().editText("C123", "/data/project/linked/new.txt", "", "new") }
        }
        assertFalse(Files.exists(dir.resolve("real/new.txt")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "/unknown/file.txt", "relative.txt", "/data/project/../outside.txt"]
    )
    fun `edit rejects unsupported paths and root escapes`(path: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().editText("C123", path, "", "new") }
        }
    }

    private fun filesystem() =
        WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem)
            .create(
                VirtualPaths(
                    sessionRoot = dir.resolve("session"),
                    skillsRoot = dir.resolve("skills"),
                    globalRoot = dir.resolve("global"),
                    workRoot = dir.resolve("work"),
                    projectRoot = dir,
                )
            )
}
