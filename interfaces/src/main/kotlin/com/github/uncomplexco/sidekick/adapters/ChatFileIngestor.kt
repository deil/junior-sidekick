package com.github.uncomplexco.sidekick.adapters

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory

internal const val MAX_MESSAGE_FILES = 3

internal class ChatFileIngestor(
    private val virtualPathsFactory: VirtualPathsFactory,
    private val imageSummarizer: ImageSummarizer,
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
    private val authorization: String? = null,
) {
    suspend fun ingest(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
        summarizeImages: Boolean = true,
    ): List<IncomingChatFile> =
        files.take(MAX_MESSAGE_FILES).mapNotNull { file ->
            val virtualPaths = virtualPathsFactory.forConversation(conversationId)
            val localPath = download(virtualPaths, file) ?: return@mapNotNull null
            file.copy(
                localPath = virtualPaths.virtualPath(localPath.toString()),
                summary =
                    if (summarizeImages && file.mimetype?.startsWith("image/") == true) {
                        when (val result = imageSummarizer.summarize(localPath)) {
                            is ImageSummarizer.Result.Success -> result.summary
                            is ImageSummarizer.Result.Failure -> {
                                log.warn(
                                    "Image summarization failed for file id={}",
                                    file.id,
                                    result.error,
                                )
                                null
                            }
                        }
                    } else {
                        null
                    },
            )
        }

    private fun download(virtualPaths: VirtualPaths, file: IncomingChatFile): Path? =
        runCatching {
                log.info("Downloading chat attachment id={} name={}", file.id, file.name)
                val request =
                    HttpRequest.newBuilder(URI.create(file.urlPrivateDownload))
                        .timeout(Duration.ofSeconds(30))
                authorization?.let { request.header("Authorization", it) }
                val response =
                    httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                check(response.statusCode() in HTTP_SUCCESS_CODES) {
                    "Chat file download failed with HTTP ${response.statusCode()}."
                }

                Files.createDirectories(virtualPaths.sessionRoot)
                val target = virtualPaths.sessionRoot.resolve(downloadFileName(file))
                Files.write(target, response.body())
                target
            }
            .getOrElse {
                log.warn("Chat file ingest failed for file id={}", file.id, it)
                null
            }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        val HTTP_SUCCESS_CODES = 200..299
        val log = LoggerFactory.getLogger(ChatFileIngestor::class.java)
    }
}

private fun downloadFileName(file: IncomingChatFile): String =
    "${sanitizeFileName(file.id)}-${sanitizeFileName(file.name)}"

private fun sanitizeFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifBlank { "file" }
