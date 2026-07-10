package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyAttachmentCollectorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `stages a workspace file before its source is deleted`() {
        val source = dir.resolve("work/report.csv")
        Files.createDirectories(source.parent)
        Files.writeString(source, "name,count\nwidgets,3\n")
        val collector = ReplyAttachmentCollector(virtualPaths())

        val attachment = collector.attach("/work/report.csv", name = null, mimeType = "text/csv")
        Files.delete(source)

        assertEquals("report.csv", attachment.name)
        assertEquals("text/csv", attachment.mimeType)
        assertEquals(21L, attachment.bytes)
        assertEquals("name,count\nwidgets,3\n", Files.readString(attachment.path))

        collector.clear()

        assertFalse(Files.exists(attachment.path))
    }

    @Test
    fun `rejects non-file workspace paths`() {
        Files.createDirectories(dir.resolve("work/output"))
        val collector = ReplyAttachmentCollector(virtualPaths())

        val error =
            assertThrows<IllegalArgumentException> {
                collector.attach("/work/output", name = null, mimeType = null)
            }

        assertTrue(error.message.orEmpty().contains("not a regular file"))
    }

    @Test
    fun `accepts a file at the size limit`() {
        val source = dir.resolve("work/ten-mebibytes.bin")
        Files.createDirectories(source.parent)
        Files.write(source, ByteArray(10 * 1024 * 1024))
        val collector = ReplyAttachmentCollector(virtualPaths())

        val attachment = collector.attach("/work/ten-mebibytes.bin", name = null, mimeType = null)

        assertEquals(10L * 1024 * 1024, Files.size(attachment.path))
        collector.clear()
    }

    @Test
    fun `rejects a file over the size limit`() {
        val source = dir.resolve("work/too-large.bin")
        Files.createDirectories(source.parent)
        Files.write(source, ByteArray(10 * 1024 * 1024 + 1))
        val collector = ReplyAttachmentCollector(virtualPaths())

        val error =
            assertThrows<IllegalArgumentException> {
                collector.attach("/work/too-large.bin", name = null, mimeType = null)
            }

        assertTrue(error.message.orEmpty().contains("10 MiB"))
    }

    private fun virtualPaths() =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = dir.resolve("project"),
        )
}
