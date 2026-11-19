package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.adapters.files.LocalWorkspaceFilesystem
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ReplyAttachmentCollectorTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `stages a workspace file before its source is deleted`() = runBlocking {
        val source = dir.resolve("work/report.csv")
        Files.createDirectories(source.parent)
        Files.writeString(source, "name,count\nwidgets,3\n")
        val collector = collector()

        val attachment = collector.attach("/work/report.csv", name = null, mimeType = "text/csv")
        Files.delete(source)

        assertEquals("report.csv", attachment.name)
        assertEquals("text/csv", attachment.mimeType)
        assertEquals(21L, attachment.bytes)
        assertEquals("name,count\nwidgets,3\n", Files.readString(attachment.path))
    }

    @Test
    fun `rejects non-file workspace paths`() = runBlocking {
        Files.createDirectories(dir.resolve("work/output"))
        val collector = collector()

        val error =
            assertThrows<IllegalArgumentException> {
                collector.attach("/work/output", name = null, mimeType = null)
            }

        assertTrue(error.message.orEmpty().contains("not a regular file"))
    }

    @Test
    fun `accepts a file at the size limit`() = runBlocking {
        val source = dir.resolve("work/ten-mebibytes.bin")
        Files.createDirectories(source.parent)
        Files.write(source, ByteArray(10 * 1024 * 1024))
        val collector = collector()

        val attachment = collector.attach("/work/ten-mebibytes.bin", name = null, mimeType = null)

        assertEquals(10L * 1024 * 1024, Files.size(attachment.path))
        collector.clear()
    }

    @Test
    fun `rejects a file over the size limit`() = runBlocking {
        val source = dir.resolve("work/too-large.bin")
        Files.createDirectories(source.parent)
        Files.write(source, ByteArray(10 * 1024 * 1024 + 1))
        val collector = collector()

        val error =
            assertThrows<IllegalArgumentException> {
                collector.attach("/work/too-large.bin", name = null, mimeType = null)
            }

        assertTrue(error.message.orEmpty().contains("10 MiB"))
    }

    @Test
    fun `clearing attachments deletes staged files without deleting workspace sources`() =
        runBlocking {
            val source =
                Files.writeString(
                    Files.createDirectories(dir.resolve("work")).resolve("report.txt"),
                    "report",
                )
            val collector = collector()
            val attachment = collector.attach("/work/report.txt", null, null)

            collector.clear()

            assertFalse(Files.exists(attachment.path))
            assertEquals("report", Files.readString(source))
            assertEquals(emptyList(), collector.collected())
        }

    @Test
    fun `downloads project files while preserving display name and inferred source mime type`() =
        runBlocking {
            Files.writeString(
                Files.createDirectories(dir.resolve("project")).resolve("report.txt"),
                "report",
            )
            val collector = collector()

            val attachment = collector.attach("/data/project/report.txt", "renamed.bin", null)

            assertEquals("renamed.bin", attachment.name)
            assertEquals("text/plain", attachment.mimeType)
            assertEquals("report", Files.readString(attachment.path))
            assertEquals(dir.resolve("session/reply-attachments"), attachment.path.parent)
            assertEquals(listOf(attachment), collector.collected())
        }

    @Test
    fun `rejects files beyond the per-reply attachment limit`() = runBlocking {
        Files.writeString(
            Files.createDirectories(dir.resolve("work")).resolve("report.txt"),
            "report",
        )
        val collector = collector()
        repeat(5) { collector.attach("/work/report.txt", null, null) }

        val error =
            assertThrows<IllegalArgumentException> {
                collector.attach("/work/report.txt", null, null)
            }

        assertEquals("A reply can include at most 5 files.", error.message)
        assertEquals(5, collector.collected().size)
    }

    private fun collector(): ReplyAttachmentCollector =
        ReplyAttachmentCollector(
            "C123",
            LocalWorkspaceFilesystem(virtualPaths()),
            dir.resolve("session"),
        )

    private fun virtualPaths() =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = dir.resolve("project"),
        )
}
