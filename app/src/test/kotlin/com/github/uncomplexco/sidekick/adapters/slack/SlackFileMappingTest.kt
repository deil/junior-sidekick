package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.slack.api.model.Attachment
import com.slack.api.model.File
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlackFileMappingTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `uses directly attached files when present`() {
        val files =
            incomingChatFiles(
                files = listOf(slackFile("F_DIRECT")),
                attachments = listOf(Attachment.builder().files(listOf(slackFile("F_FORWARDED"))).build()),
            )

        assertEquals(listOf("F_DIRECT"), files.map { it.id })
    }

    @Test
    fun `falls back to files from attachments`() {
        val files =
            incomingChatFiles(
                files = emptyList(),
                attachments = listOf(Attachment.builder().files(listOf(slackFile("F_FORWARDED"))).build()),
            )

        assertEquals(listOf("F_FORWARDED"), files.map { it.id })
    }

    @Test
    fun `keeps only first three files`() {
        val files =
            incomingChatFiles(
                files = listOf(slackFile("F1"), slackFile("F2"), slackFile("F3"), slackFile("F4")),
                attachments = emptyList(),
            )

        assertEquals(listOf("F1", "F2", "F3"), files.map { it.id })
    }

    @Test
    fun `ingested file path is relative to session folder`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/F1") { exchange ->
            val body = "hello".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val sessionId = SessionId("C123", "1700000000.000")
            val ingestor = SlackFileIngestor("token", dir)
            val file =
                incomingChatFiles(
                    files = listOf(slackFile("F1", "http://127.0.0.1:${server.address.port}/F1")),
                    attachments = emptyList(),
                ).single()

            val ingested = ingestor.ingest(sessionId, listOf(file)).single()

            assertEquals("files/F1-F1.md", ingested.localPath)
            assertTrue(Files.exists(sessionId.folder(dir).resolve(ingested.localPath!!)))
        } finally {
            server.stop(0)
        }
    }

    private fun slackFile(id: String): File =
        slackFile(id, "https://files.slack.com/$id")

    private fun slackFile(
        id: String,
        urlPrivateDownload: String,
    ): File =
        File
            .builder()
            .id(id)
            .name("$id.md")
            .mimetype("text/markdown")
            .filetype("markdown")
            .urlPrivateDownload(urlPrivateDownload)
            .build()
}
