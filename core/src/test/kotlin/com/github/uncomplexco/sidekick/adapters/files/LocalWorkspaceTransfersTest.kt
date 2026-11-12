package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LocalWorkspaceTransfersTest {
    @TempDir lateinit var dir: Path

    private val bytes = byteArrayOf(0, 1, -1, 13, 10, 0)

    @Test
    fun `uploads binary file into session attachments and returns visible path`() = runBlocking {
        val source = Files.write(dir.resolve("incoming.bin"), bytes)
        val filesystem = filesystem()

        val result = filesystem.upload("project", "thread", source)

        assertContentEquals(bytes, Files.readAllBytes(dir.resolve("history/incoming.bin")))
        assertContentEquals(bytes, Files.readAllBytes(source))
        assertEquals("/data/session/incoming.bin", result.path)
        assertEquals(FileKind.FILE, result.metadata.kind)
        assertEquals(6L, result.metadata.sizeBytes)
    }

    @ParameterizedTest(name = "downloads {0} and creates local destination parents")
    @CsvSource("work/output.bin, work", "attachments/output.bin, history")
    fun `downloads binary file and creates local destination parents`(
        sessionPath: String,
        localDirectory: String,
    ) = runBlocking {
        val source =
            Files.write(
                Files.createDirectories(dir.resolve(localDirectory)).resolve("output.bin"),
                bytes,
            )
        val destination = dir.resolve("replies/output.bin")
        val filesystem = filesystem()

        val result = filesystem.download("project", "thread", sessionPath, destination)

        assertContentEquals(bytes, Files.readAllBytes(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
        assertEquals(FileKind.FILE, result.kind)
        assertEquals(6L, result.sizeBytes)
    }

    @Test
    fun `upload rejects existing destination without overwriting it`() = runBlocking {
        val source = Files.write(dir.resolve("incoming.bin"), bytes)
        val destination =
            Files.write(
                Files.createDirectories(dir.resolve("history")).resolve("incoming.bin"),
                byteArrayOf(1),
            )
        val filesystem = filesystem()

        assertFailsWith<FileAlreadyExistsException> {
            filesystem.upload("project", "thread", source)
        }

        assertContentEquals(byteArrayOf(1), Files.readAllBytes(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `download rejects existing destination without overwriting it`() = runBlocking {
        val source =
            Files.write(Files.createDirectories(dir.resolve("work")).resolve("output.bin"), bytes)
        val destination = Files.write(dir.resolve("existing.bin"), byteArrayOf(1))
        val filesystem = filesystem()

        assertFailsWith<FileAlreadyExistsException> {
            filesystem.download("project", "thread", "work/output.bin", destination)
        }

        assertContentEquals(byteArrayOf(1), Files.readAllBytes(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `upload preserves attachment already stored in the local session`() = runBlocking {
        val source =
            Files.write(
                Files.createDirectories(dir.resolve("history")).resolve("incoming.bin"),
                bytes,
            )
        val filesystem = filesystem()

        val result = filesystem.upload("project", "thread", source)

        assertEquals("/data/session/incoming.bin", result.path)
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `download rejects source escaping workspace root`() = runBlocking {
        val source = Files.write(dir.resolve("outside.bin"), bytes)
        val destination = dir.resolve("history/output.bin")
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.download("project", "thread", "work/../outside.bin", destination)
        }

        assertFalse(Files.exists(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `upload rejects directory source without creating a destination`() = runBlocking {
        val source = Files.createDirectories(dir.resolve("incoming"))
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.upload("project", "thread", source)
        }

        assertFalse(Files.exists(dir.resolve("history")))
    }

    @Test
    fun `download rejects directory source without creating a destination`() = runBlocking {
        Files.createDirectories(dir.resolve("work/output"))
        val destination = dir.resolve("history/output")
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.download("project", "thread", "work/output", destination)
        }

        assertFalse(Files.exists(destination.parent))
    }

    @Test
    fun `download rejects absolute workspace paths outside the session interface`() = runBlocking {
        val source =
            Files.write(
                Files.createDirectories(dir.resolve("project")).resolve("report.bin"),
                bytes,
            )
        val destination = dir.resolve("replies/report.bin")
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.download("project", "thread", "/data/project/report.bin", destination)
        }

        assertFalse(Files.exists(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `uploads with the same filename stay in their bound thread workspaces`() = runBlocking {
        val first =
            Files.write(Files.createDirectories(dir.resolve("first")).resolve("input.bin"), bytes)
        val second =
            Files.write(
                Files.createDirectories(dir.resolve("second")).resolve("input.bin"),
                byteArrayOf(1),
            )
        val firstWorkspace = filesystem(dir.resolve("first"))
        val secondWorkspace = filesystem(dir.resolve("second"))

        firstWorkspace.upload("project", "first", first)
        secondWorkspace.upload("project", "second", second)

        assertContentEquals(bytes, Files.readAllBytes(dir.resolve("first/history/input.bin")))
        assertContentEquals(
            byteArrayOf(1),
            Files.readAllBytes(dir.resolve("second/history/input.bin")),
        )
    }

    private fun filesystem(root: Path = dir): WorkspaceFilesystem =
        LocalWorkspaceFilesystem(
            VirtualPaths(
                sessionRoot = root.resolve("history"),
                skillsRoot = root.resolve("skills"),
                globalRoot = root.resolve("global"),
                workRoot = root.resolve("work"),
                projectRoot = root.resolve("project"),
            )
        )
}
