package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.GrepMatch
import com.github.uncomplexco.sidekick.ports.filesystem.GrepResult
import java.nio.file.Files
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

class LocalWorkspaceFilesystemGrepTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `grep matches regex and include glob with one based line numbers`() = runBlocking {
        file("project/src/App.kt", "other\nneedle1\nneedle2\n")
        file("project/src/nested/Util.kt", "needle3\n")
        file("project/src/Ignore.java", "needle4\n")

        val result = filesystem().grep("C123", "/data/project/src", "needle[13]", "**/*.kt")

        assertEquals(
            GrepResult(
                listOf(
                    GrepMatch("/data/project/src/App.kt", 2, "needle1", modifiedAt),
                    GrepMatch("/data/project/src/nested/Util.kt", 1, "needle3", modifiedAt),
                ),
                2,
                false,
            ),
            result,
        )
    }

    @Test
    fun `grep searches a single file without searching siblings`() = runBlocking {
        file("project/App.kt", "needle\n")
        file("project/Other.kt", "needle\n")

        val result = filesystem().grep("C123", "/data/project/App.kt", "needle", "**/*.kt")

        assertEquals(listOf("/data/project/App.kt"), result.matches.map { it.path })
    }

    @Test
    fun `grep applies include glob to a single file`() = runBlocking {
        file("project/App.kt", "needle\n")

        val result = filesystem().grep("C123", "/data/project/App.kt", "needle", "**/*.java")

        assertEquals(GrepResult(emptyList(), 0, false), result)
    }

    @Test
    fun `grep orders newest files first then path and line`() = runBlocking {
        file("project/Old.txt", "needle\n")
        val newer = Instant.parse("2026-01-02T00:00:00Z")
        val b = file("project/B.txt", "needle\n")
        val a = file("project/A.txt", "needle\nneedle\n")
        Files.setLastModifiedTime(b, FileTime.from(newer))
        Files.setLastModifiedTime(a, FileTime.from(newer))

        val result = filesystem().grep("C123", "/data/project", "needle")

        assertEquals(
            listOf(
                GrepMatch("/data/project/A.txt", 1, "needle", newer),
                GrepMatch("/data/project/A.txt", 2, "needle", newer),
                GrepMatch("/data/project/B.txt", 1, "needle", newer),
                GrepMatch("/data/project/Old.txt", 1, "needle", modifiedAt),
            ),
            result.matches,
        )
    }

    @Test
    fun `grep excludes git metadata binary files malformed utf8 and symbolic links`() =
        runBlocking {
            file("project/visible.txt", "needle\n")
            file("project/.git/hidden.txt", "needle\n")
            file("project/archive.zip", "needle\n")
            Files.write(dir.resolve("project/binary.txt"), byteArrayOf(0x6E, 0x00))
            Files.write(dir.resolve("project/malformed.txt"), byteArrayOf(0x6E, 0x80.toByte()))
            file("outside/hidden.txt", "needle\n")
            Files.createSymbolicLink(
                dir.resolve("project/link.txt"),
                dir.resolve("outside/hidden.txt"),
            )
            Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("outside"))

            val result = filesystem().grep("C123", "/data/project", "needle|n")

            assertEquals(listOf("/data/project/visible.txt"), result.matches.map { it.path })
        }

    @Test
    fun `grep does not clip matching lines`() = runBlocking {
        val text = "needle" + "x".repeat(3000)
        file("project/long.txt", text)

        val result = filesystem().grep("C123", "/data/project", "needle")

        assertEquals(text, result.matches.single().text)
    }

    @Test
    fun `grep counts all matching lines before truncation`() = runBlocking {
        file("project/many.txt", "needle\n".repeat(101))

        val result = filesystem().grep("C123", "/data/project", "needle")

        assertEquals(100, result.matches.size)
        assertEquals(101, result.total)
        assertTrue(result.truncated)
    }

    @Test
    fun `grep applies one limit across aggregate roots`() = runBlocking {
        file("project/many.txt", "needle\n".repeat(100))
        file("global/match.txt", "needle\n")

        val result = filesystem().grep("C123", "/data/", "needle")

        assertEquals(100, result.matches.size)
        assertEquals(101, result.total)
        assertEquals("/data/global/match.txt", result.matches.first().path)
        assertTrue(result.truncated)
    }

    @Test
    fun `grep searches all virtual roots and skips missing roots`() = runBlocking {
        file("project/match.txt", "needle\n")
        file("work/match.txt", "needle\n")

        val result = filesystem().grep("C123", "/", "needle")

        assertEquals(
            listOf("/data/project/match.txt", "/work/match.txt"),
            result.matches.map { it.path },
        )
    }

    @Test
    fun `grep returns empty results when nothing matches`() = runBlocking {
        file("project/hello.txt", "hello\n")

        val result = filesystem().grep("C123", "/data/project", "needle")

        assertEquals(GrepResult(emptyList(), 0, false), result)
    }

    @Test
    fun `grep rejects a symbolic link search directory`() {
        Files.createDirectories(dir.resolve("project/real"))
        Files.createSymbolicLink(dir.resolve("project/linked"), dir.resolve("project/real"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().grep("C123", "/data/project/linked", "needle") }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/unknown", "relative", "/data/project/../outside"])
    fun `grep rejects unsupported paths and root escapes`(path: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().grep("C123", path, "needle") }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "["])
    fun `grep rejects blank and invalid patterns`(pattern: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().grep("C123", "/", pattern) }
        }
    }

    private fun file(path: String, content: String): Path {
        val target = dir.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
        Files.setLastModifiedTime(target, FileTime.from(modifiedAt))

        return target
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

    private companion object {
        val modifiedAt = Instant.parse("2026-01-01T00:00:00Z")
    }
}
