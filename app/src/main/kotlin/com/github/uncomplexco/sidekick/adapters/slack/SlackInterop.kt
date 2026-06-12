package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.workspace.toSessionBasedPath
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import com.github.uncomplexco.sidekick.ports.chat.ReplyToMessage
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.model.Attachment
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import com.slack.api.model.File as SlackFile

fun replyInSlack(
    ctx: EventContext,
    threadTs: String?,
): ReplyToMessage =
    { text ->
        val postResponse =
            ctx.client().chatPostMessage { req ->
                req.channel(ctx.channelId)
                if (threadTs != null) {
                    req.threadTs(threadTs)
                }

                req.markdownText(text)
            }

        if (!postResponse.isOk) {
            log.warn("Slack markdown post failed, fallback to plain text")
            ctx.say(text)
        }

        ReplyResult(
            messageId = postResponse.ts,
            timestamp = slackTsToMillis(postResponse.ts),
        )
    }

fun slackActivityIndicator(
    ctx: EventContext,
    threadTs: String,
): ChatActivityIndicator =
    object : ChatActivityIndicator {
        val STATUS_LISTENING = "listening..."
        val STATUS_THINKING = "thinking..."
        private var turnActive = false

        override fun start(text: String?) {
            turnActive = true

            setStatus(status = STATUS_LISTENING, emoji = ":eyes:", loadingMessages = listOf(STATUS_LISTENING))
        }

        override fun `continue`(text: String?) {
            setStatus(
                status = STATUS_THINKING,
                emoji = ":face_in_clouds:",
                loadingMessages = listOf(text ?: STATUS_THINKING),
            )
        }

        override fun toolCall(name: String) {
            setStatus(
                status = STATUS_THINKING,
                emoji = ":satellite_antenna:",
                loadingMessages = listOf("-> $name..."),
            )
        }

        override fun clear() {
            if (turnActive) {
                `continue`()
            } else {
                setStatus("")
            }
        }

        override fun endTurn() {
            turnActive = false
            setStatus("")
        }

        private fun setStatus(
            status: String,
            emoji: String? = null,
            loadingMessages: List<String>? = null,
        ) {
            runCatching {
                val response =
                    ctx.client().assistantThreadsSetStatus { req ->
                        req.channelId(ctx.channelId)
                        req.threadTs(threadTs)
                        req.status(status)
                        if (loadingMessages != null) {
                            req.loadingMessages(loadingMessages)
                        }

                        emoji?.also { req.iconEmoji(it) }

                        req
                    }
                if (!response.isOk) {
                    log.warn("Slack assistant status update failed: {}", response.error)
                }
            }.onFailure {
                log.warn("Slack assistant status update failed", it)
            }
        }
    }

private val slackAssistantStatusTexts =
    listOf(
        "consulting the tiny compiler",
        "untangling the thread",
        "thinking with tabs open",
    )

internal fun incomingChatFiles(
    files: List<SlackFile>?,
    attachments: List<Attachment>?,
): List<IncomingChatFile> {
    val directFiles = files.toIncomingChatFiles()
    return directFiles.ifEmpty {
        attachments
            .orEmpty()
            .flatMap { it.files.orEmpty() }
            .toIncomingChatFiles()
    }
}

class SlackFileIngestor(
    private val slackBotToken: String,
    private val stateRoot: Path,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) {
    fun ingest(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> =
        files
            .take(MAX_MESSAGE_FILES)
            .mapNotNull { file ->
                val localPath = download(conversationId, file) ?: return@mapNotNull null
                file.copy(localPath = localPath.toSessionBasedPath())
            }

    private fun download(
        conversationId: ConversationId,
        file: IncomingChatFile,
    ): String? =
        runCatching {
            val response =
                httpClient.send(
                    HttpRequest
                        .newBuilder(URI.create(file.urlPrivateDownload))
                        .timeout(Duration.ofSeconds(30))
                        .header("Authorization", "Bearer $slackBotToken")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
            check(response.statusCode() in 200..299) { "Slack file download failed with HTTP ${response.statusCode()}." }

            val sessionFolder = conversationId.folder(stateRoot)
            val folder = sessionFolder.resolve("attachments")
            Files.createDirectories(folder)
            val target = folder.resolve(downloadFileName(file))
            Files.write(target, response.body())
            return@runCatching sessionFolder.relativize(target).toString()
        }.getOrElse {
            log.warn("Slack file ingest failed for file id={}", file.id, it)
            null
        }
}

private fun List<SlackFile>?.toIncomingChatFiles(): List<IncomingChatFile> =
    this
        .orEmpty()
        .take(MAX_MESSAGE_FILES)
        .mapNotNull { file ->
            if (file.id == null || file.urlPrivateDownload == null) return@mapNotNull null

            IncomingChatFile(
                id = file.id,
                name = file.name,
                mimetype = file.mimetype,
                filetype = file.filetype,
                permalink = file.permalink,
                urlPrivateDownload = file.urlPrivateDownload,
                localPath = null,
            )
        }

internal fun downloadFileName(file: IncomingChatFile): String {
    val rawName = file.name ?: file.id
    return "${sanitizeFileName(file.id)}-${sanitizeFileName(rawName)}"
}

private fun sanitizeFileName(value: String): String =
    value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .ifBlank { "file" }

internal fun slackTsToMillis(ts: String): Long = (ts.toDouble().times(1000)).toLong()

internal fun isBotsOwnMessage(
    senderBotId: String?,
    ctx: EventContext,
): Boolean = senderBotId != null && senderBotId == ctx.botUserId

internal fun containsMention(
    text: String,
    username: String,
): Boolean = text.contains("<@$username>")

internal val log: Logger = LoggerFactory.getLogger(SlackAppFactory::class.java)

private const val MAX_MESSAGE_FILES = 3
