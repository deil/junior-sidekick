package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceTransfers
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WorkspaceDownloadTest {
    @TempDir lateinit var dir: Path
    private val bytes = byteArrayOf(0, 1, -1, 13, 10, 0)

    @ParameterizedTest(name = "downloads binary file from {0}")
    @CsvSource(
        "/data/project/report.bin,project",
        "/work/report.bin,work",
        "/data/session/report.bin,session",
        "/data/global/report.bin,global",
        "/data/skills/report.bin,skills",
    )
    fun `downloads file from any workspace root and preserves source attributes`(
        workspacePath: String,
        directory: String,
    ) = runBlocking {
        val source =
            Files.write(
                Files.createDirectories(dir.resolve(directory)).resolve("report.bin"),
                bytes,
            )
        val modifiedAt = Instant.parse("2025-01-01T00:00:00Z")
        Files.setLastModifiedTime(source, FileTime.from(modifiedAt))
        val destination = dir.resolve("conversation/replies/report.bin")
        val filesystem = filesystem()

        val metadata = filesystem.download("C123", workspacePath, destination)

        assertContentEquals(bytes, Files.readAllBytes(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
        assertEquals(FileKind.FILE, metadata.kind)
        assertEquals(6L, metadata.sizeBytes)
        assertEquals(modifiedAt, metadata.modifiedAt)
        assertEquals(modifiedAt, Files.getLastModifiedTime(destination).toInstant())
    }

    @Test
    fun `rejects existing destination without replacing its contents`() = runBlocking {
        val source =
            Files.write(
                Files.createDirectories(dir.resolve("project")).resolve("report.bin"),
                bytes,
            )
        val destination = Files.write(dir.resolve("existing.bin"), byteArrayOf(1))
        val filesystem = filesystem()

        assertFailsWith<FileAlreadyExistsException> {
            filesystem.download("C123", "/data/project/report.bin", destination)
        }

        assertContentEquals(byteArrayOf(1), Files.readAllBytes(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `rejects directory sources without creating destination directories`() = runBlocking {
        Files.createDirectories(dir.resolve("project/output"))
        val destination = dir.resolve("conversation/output")
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.download("C123", "/data/project/output", destination)
        }

        assertFalse(Files.exists(destination.parent))
    }

    @Test
    fun `rejects paths escaping the workspace without copying external files`() = runBlocking {
        val source = Files.write(dir.resolve("outside.bin"), bytes)
        val destination = dir.resolve("conversation/output.bin")
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.download("C123", "/data/project/../outside.bin", destination)
        }

        assertFalse(Files.exists(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test
    fun `rejects symbolic links without copying their targets`() = runBlocking {
        val source = Files.write(dir.resolve("outside.bin"), bytes)
        val project = Files.createDirectories(dir.resolve("project"))
        Files.createSymbolicLink(project.resolve("link.bin"), source)
        val destination = dir.resolve("conversation/output.bin")
        val filesystem = filesystem()

        assertFailsWith<IllegalArgumentException> {
            filesystem.download("C123", "/data/project/link.bin", destination)
        }

        assertFalse(Files.exists(destination))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    private fun filesystem(): WorkspaceTransfers =
        LocalWorkspaceFilesystem(
                VirtualPaths(
                    sessionRoot = dir.resolve("session"),
                    skillsRoot = dir.resolve("skills"),
                    globalRoot = dir.resolve("global"),
                    workRoot = dir.resolve("work"),
                    projectRoot = dir.resolve("project"),
                )
            )
            .transfers
}
