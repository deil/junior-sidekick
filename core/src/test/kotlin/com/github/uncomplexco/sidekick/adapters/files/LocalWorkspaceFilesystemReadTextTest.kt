package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadOptions
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadResult
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LocalWorkspaceFilesystemReadTextTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest
    @ValueSource(
        strings = ["first\nsecond\n", "first\r\nsecond\r\n", "first\rsecond", "first\nsecond"]
    )
    fun `unbounded read preserves original line endings`(content: String) = runBlocking {
        Files.writeString(dir.resolve("text.txt"), content)

        val result = filesystem().readText("C123", "/data/project/text.txt")

        assertEquals(TextReadResult(content, false, 2), result)
    }

    @Test
    fun `unbounded read preserves long lines`() = runBlocking {
        val content = "a".repeat(60000)
        Files.writeString(dir.resolve("text.txt"), content)

        val result = filesystem().readText("C123", "/data/project/text.txt")

        assertEquals(TextReadResult(content, false, 1), result)
    }

    @Test
    fun `empty file has zero lines`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "")

        val result = filesystem().readText("C123", "/data/project/text.txt")

        assertEquals(TextReadResult("", false, 0), result)
    }

    @Test
    fun `line range preserves blank lines and reports total`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "first\r\n\r\nthird\r\nfourth")

        val result = filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(2, 2))

        assertEquals(TextReadResult("\r\nthird\r\n", true, 4), result)
    }

    @Test
    fun `offset without limit returns the rest of the file`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "first\nsecond\n")

        val result =
            filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(offset = 2))

        assertEquals(TextReadResult("second\n", false, 2), result)
    }

    @Test
    fun `byte cap stops without reading the remaining lines`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "first\nsecond\nthird\n")

        val result =
            filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(maxBytes = 6))

        assertEquals(TextReadResult("first\n", true, null), result)
    }

    @Test
    fun `byte cap does not split a multibyte character`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "éé")

        val result =
            filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(maxBytes = 3))

        assertEquals(TextReadResult("é", true, null, lastLineTruncated = true), result)
    }

    @Test
    fun `byte cap does not split a supplementary character`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "a😀b")

        val result =
            filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(maxBytes = 4))

        assertEquals(TextReadResult("a", true, null, lastLineTruncated = true), result)
    }

    @Test
    fun `exact byte cap at end of file is not truncated`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "😀")

        val result =
            filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(maxBytes = 4))

        assertEquals(TextReadResult("😀", false, 1), result)
    }

    @Test
    fun `byte cap applies only to lines after offset`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "ignored\nabc")

        val result =
            filesystem()
                .readText(
                    "C123",
                    "/data/project/text.txt",
                    TextReadOptions(offset = 2, maxBytes = 3),
                )

        assertEquals(TextReadResult("abc", false, 2), result)
    }

    @Test
    fun `exact line cap at end of file is not truncated`() = runBlocking {
        Files.writeString(dir.resolve("text.txt"), "first\nsecond\n")

        val result =
            filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(maxLines = 2))

        assertEquals(TextReadResult("first\nsecond\n", false, 2), result)
    }

    @Test
    fun `read rejects an offset beyond end of file`() {
        Files.writeString(dir.resolve("text.txt"), "first\n")

        assertThrows<IllegalArgumentException> {
            runBlocking {
                filesystem().readText("C123", "/data/project/text.txt", TextReadOptions(offset = 2))
            }
        }
    }

    @Test
    fun `read rejects binary content`() {
        Files.write(dir.resolve("text.txt"), byteArrayOf(0x61, 0x00, 0x62))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().readText("C123", "/data/project/text.txt") }
        }
    }

    @Test
    fun `read rejects a binary extension`() {
        Files.writeString(dir.resolve("archive.zip"), "text")

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().readText("C123", "/data/project/archive.zip") }
        }
    }

    @Test
    fun `read rejects malformed utf8`() {
        Files.write(dir.resolve("text.txt"), byteArrayOf(0x61, 0x80.toByte()))

        assertThrows<MalformedInputException> {
            runBlocking { filesystem().readText("C123", "/data/project/text.txt") }
        }
    }

    @Test
    fun `read rejects symbolic links`() {
        Files.writeString(dir.resolve("text.txt"), "text")
        Files.createSymbolicLink(dir.resolve("linked.txt"), dir.resolve("text.txt"))

        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().readText("C123", "/data/project/linked.txt") }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            ["/unknown/text.txt", "relative.txt", "/data/project/../outside.txt", "/data/project"]
    )
    fun `read rejects unsupported paths root escapes and directories`(path: String) {
        assertThrows<IllegalArgumentException> {
            runBlocking { filesystem().readText("C123", path) }
        }
    }

    private fun filesystem() =
        WorkspaceFilesystemFactory()
            .create(
                VirtualPaths(
                    sessionRoot = dir,
                    skillsRoot = dir.resolve("skills"),
                    globalRoot = dir.resolve("global"),
                    workRoot = dir.resolve("work"),
                    projectRoot = dir,
                )
            )
}
