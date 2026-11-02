package com.github.uncomplexco.sidekick.adapters

import com.github.uncomplexco.sidekick.adapters.discord.DiscordIncomingMessage
import com.github.uncomplexco.sidekick.adapters.discord.DiscordIngress
import com.github.uncomplexco.sidekick.adapters.discord.DiscordMessageKind
import com.github.uncomplexco.sidekick.adapters.discord.DiscordSentMessage
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock

class ChatFileIngestorTest {
    @TempDir lateinit var dir: Path
    private lateinit var server: HttpServer
    private lateinit var paths: VirtualPathsFactory
    private val conversationId = ConversationId("123", "456")
    private val requests = mutableListOf<String>()
    private val authorizationHeaders = mutableListOf<String?>()

    @BeforeEach
    fun setUp() {
        paths =
            VirtualPathsFactory(
                AgentConfig("Sidekick", dir.toString(), dir.resolve("workspace").toString()),
                ChatPlatform.DISCORD,
            )
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            authorizationHeaders += exchange.requestHeaders.getFirst("Authorization")
            val body = "file contents".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `Discord download stores file in session workspace without bot authorization`() =
        runBlocking {
            // Arrange
            val ingestor = ChatFileIngestor(paths, mock(ImageSummarizer::class.java))
            val received = mutableListOf<IncomingChatFile>()
            val ingress =
                DiscordIngress(ingestor) { _, incoming, chat ->
                    received += chat.ingestFiles(conversationId, incoming.files)
                }
            val message =
                DiscordIncomingMessage(
                    id = "message",
                    channelId = "123",
                    createdAtMs = 1,
                    authorId = "user",
                    authorName = "User",
                    text = "",
                    files = listOf(file("1")),
                    kind = DiscordMessageKind.DIRECT_MESSAGE,
                )

            // Act
            ingress.receive(message, "bot") { DiscordSentMessage("reply", 2) }

            // Assert
            val ingested = received.single()
            assertEquals("/data/session/1-report.csv", ingested.localPath)
            assertEquals(
                "file contents",
                Files.readString(
                    paths.forConversation(conversationId).sessionRoot.resolve("1-report.csv")
                ),
            )
            assertEquals(listOf<String?>(null), authorizationHeaders)
        }

    @Test
    fun `download includes configured Slack authorization`() = runBlocking {
        val ingestor =
            ChatFileIngestor(
                paths,
                mock(ImageSummarizer::class.java),
                authorization = "Bearer token",
            )

        val ingested = ingestor.ingest(conversationId, listOf(file("1")))

        assertEquals(1, ingested.size)
        assertEquals(listOf<String?>("Bearer token"), authorizationHeaders)
    }

    @Test
    fun `download keeps only first three files`() = runBlocking {
        val ingestor = ChatFileIngestor(paths, mock(ImageSummarizer::class.java))

        val ingested = ingestor.ingest(conversationId, (1..4).map { file(it.toString()) })

        assertEquals(listOf("1", "2", "3"), ingested.map { it.id })
        assertEquals(listOf("/1", "/2", "/3"), requests)
    }

    @Test
    fun `failed download is omitted while remaining files are ingested`() = runBlocking {
        val ingestor = ChatFileIngestor(paths, mock(ImageSummarizer::class.java))

        val ingested = ingestor.ingest(conversationId, listOf(file("missing"), file("2")))

        assertEquals(listOf("2"), ingested.map { it.id })
        assertEquals(
            "file contents",
            Files.readString(
                paths.forConversation(conversationId).sessionRoot.resolve("2-report.csv")
            ),
        )
    }

    @Test
    fun `image summary is included with downloaded Discord image`() = runBlocking {
        val ingestor = ChatFileIngestor(paths, DownloadedImageSummarizer)

        val ingested =
            ingestor
                .ingest(
                    conversationId,
                    listOf(file("1").copy(name = "screen.png", mimetype = "image/png")),
                )
                .single()

        assertEquals("Downloaded image: file contents", ingested.summary)
        assertEquals("/data/session/1-screen.png", ingested.localPath)
    }

    @Test
    fun `image summarization can be disabled`() = runBlocking {
        val ingestor = ChatFileIngestor(paths, DownloadedImageSummarizer)

        val ingested =
            ingestor
                .ingest(
                    conversationId,
                    listOf(file("1").copy(mimetype = "image/png")),
                    summarizeImages = false,
                )
                .single()

        assertNull(ingested.summary)
        assertEquals("/data/session/1-report.csv", ingested.localPath)
    }

    private fun file(id: String) =
        IncomingChatFile(
            id,
            "report.csv",
            "text/csv",
            "csv",
            "http://127.0.0.1:${server.address.port}/$id",
            "https://discord.com/file/$id",
            null,
        )
}

private object DownloadedImageSummarizer : ImageSummarizer {
    override suspend fun summarize(imagePath: Path): ImageSummarizer.Result =
        ImageSummarizer.Result.Success("Downloaded image: ${Files.readString(imagePath)}")
}
