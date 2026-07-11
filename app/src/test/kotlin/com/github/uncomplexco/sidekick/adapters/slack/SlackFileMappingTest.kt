package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import com.slack.api.model.Attachment
import com.slack.api.model.File
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
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
    fun `ingested file path is session based`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/F1") { exchange ->
                val body = "hello".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()

            try {
                val conversationId = ConversationId("C123", "1700000000.000")
                val ingestor =
                    SlackFileIngestor(
                        slackBotToken = "token",
                        virtualPathsFactory =
                            VirtualPathsFactory(AgentConfig("Sidekick", dir.toString(), dir.resolve("workspace").toString())),
                        imageSummarizer = FailingImageSummarizer,
                    )
                val file =
                    incomingChatFiles(
                        files = listOf(slackFile("F1", "http://127.0.0.1:${server.address.port}/F1")),
                        attachments = emptyList(),
                    ).single()

                val ingested = ingestor.ingest(conversationId, listOf(file)).single()

                assertEquals("/data/session/F1-F1.md", ingested.localPath)
                assertTrue(Files.exists(conversationId.folder(dir).resolve("attachments/F1-F1.md")))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `ingested images are summarized`() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/F1") { exchange ->
                val body = byteArrayOf(1, 2, 3)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()

            try {
                val conversationId = ConversationId("C123", "1700000000.000")
                var summarizedPath: Path? = null
                val ingestor =
                    SlackFileIngestor(
                        slackBotToken = "token",
                        virtualPathsFactory =
                            VirtualPathsFactory(AgentConfig("Sidekick", dir.toString(), dir.resolve("workspace").toString())),
                        imageSummarizer =
                            object : ImageSummarizer {
                                override suspend fun summarize(imagePath: Path): ImageSummarizer.Result {
                                    summarizedPath = imagePath
                                    return ImageSummarizer.Result.Success("Visible title: Dashboard")
                                }
                            },
                    )
                val file =
                    slackFile(
                        id = "F1",
                        urlPrivateDownload = "http://127.0.0.1:${server.address.port}/F1",
                        name = "screen.png",
                        mimetype = "image/png",
                        filetype = "png",
                    )
                val incoming = incomingChatFiles(listOf(file), emptyList()).single()

                val ingested = ingestor.ingest(conversationId, listOf(incoming)).single()

                assertEquals("Visible title: Dashboard", ingested.summary)
                assertEquals(conversationId.folder(dir).resolve("attachments/F1-screen.png"), summarizedPath)
            } finally {
                server.stop(0)
            }
        }

    private fun slackFile(id: String): File = slackFile(id, "https://files.slack.com/$id")

    private fun slackFile(
        id: String,
        urlPrivateDownload: String,
        name: String = "$id.md",
        mimetype: String = "text/markdown",
        filetype: String = "markdown",
    ): File =
        File
            .builder()
            .id(id)
            .name(name)
            .mimetype(mimetype)
            .filetype(filetype)
            .permalink("https://slack.example/files/$id")
            .urlPrivateDownload(urlPrivateDownload)
            .build()

    private object FailingImageSummarizer : ImageSummarizer {
        override suspend fun summarize(imagePath: Path): ImageSummarizer.Result =
            error("non-image file should not be summarized")
    }
}
