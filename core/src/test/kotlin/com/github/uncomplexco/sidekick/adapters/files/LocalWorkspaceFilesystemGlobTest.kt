package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.GlobMatch
import com.github.uncomplexco.sidekick.ports.filesystem.GlobResult
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalWorkspaceFilesystemGlobTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `glob matches root and nested files with newest first and path as tie breaker`() =
        runBlocking {
            val older = Instant.parse("2026-01-01T00:00:00Z")
            val newer = Instant.parse("2026-01-02T00:00:00Z")
            file("project/src/Old.kt", older)
            file("project/src/nested/B.kt", newer)
            file("project/src/nested/A.kt", newer)
            file("project/src/ignored.java", newer)

            val result = filesystem().glob("C123", "/data/project/src/", "**/*.kt")

            assertEquals(
                GlobResult(
                    listOf(
                        GlobMatch("/data/project/src/nested/A.kt", newer),
                        GlobMatch("/data/project/src/nested/B.kt", newer),
                        GlobMatch("/data/project/src/Old.kt", older),
                    ),
                    false,
                ),
                result,
            )
        }

    @Test
    fun `glob excludes git metadata and symbolic link entries`() = runBlocking {
        file("project/visible.md")
        file("project/.git/hidden.md")
        file("outside/hidden.md")
        Files.createSymbolicLink(dir.resolve("project/link.md"), dir.resolve("outside/hidden.md"))
        Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("outside"))

        val result = filesystem().glob("C123", "/data/project", "**/*.md")

        assertEquals(listOf("/data/project/visible.md"), result.matches.map { it.path })
    }

    @Test
    fun `glob caps results at one hundred`() = runBlocking {
        repeat(101) { file("project/file-$it.md") }

        val result = filesystem().glob("C123", "/data/project", "**/*.md")

        assertEquals(100, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `glob applies one limit across aggregate roots`() = runBlocking {
        val newer = Instant.parse("2026-01-02T00:00:00Z")
        repeat(100) { file("project/file-$it.md") }
        file("global/newest.md", newer)

        val result = filesystem().glob("C123", "/data", "**/*.md")

        assertEquals(100, result.matches.size)
        assertEquals("/data/global/newest.md", result.matches.first().path)
        assertTrue(result.truncated)
    }

    @Test
    fun `glob searches all virtual roots and skips missing ones`() = runBlocking {
        file("project/project.md")
        file("work/work.md")

        val result = filesystem().glob("C123", "/", "**/*.md")

        assertEquals(
            listOf("/data/project/project.md", "/work/work.md"),
            result.matches.map { it.path },
        )
    }

    @Test
    fun `glob returns empty results when nothing matches`() = runBlocking {
        Files.createDirectories(dir.resolve("project"))

        val result = filesystem().glob("C123", "/data/project", "**/*.md")

        assertEquals(GlobResult(emptyList(), false), result)
    }

    @Test
    fun `glob rejects a symbolic link search directory`() {
        Files.createDirectories(dir.resolve("project/real"))
        Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("project/real"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().glob("C123", "/data/project/linked", "**/*.md") }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/unknown", "relative", "/data/project/../outside"])
    fun `glob rejects unsupported paths and root escapes`(path: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().glob("C123", path, "**/*.md") }
        }
    }

    @Test
    fun `glob rejects a file as the search directory`() {
        file("project/file.md")

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().glob("C123", "/data/project/file.md", "**/*.md") }
        }
    }

    @Test
    fun `glob fails for a missing search directory`() {
        assertThrows<NoSuchFileException> {
            runBlocking { filesystem().glob("C123", "/data/project/missing", "**/*.md") }
        }
    }

    private fun file(path: String, modifiedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")) {
        val target = dir.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, "content")
        Files.setLastModifiedTime(target, FileTime.from(modifiedAt))
    }

    private fun filesystem() =
        WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem)
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
