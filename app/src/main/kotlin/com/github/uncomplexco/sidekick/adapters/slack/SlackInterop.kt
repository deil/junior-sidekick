package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.TurnActivityIndicator
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.Loggers
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

class SlackChatPlatformAdapter(
    private val ctx: EventContext,
    private val threadId: ChatThreadId,
    private val historyLoader: (ConversationId) -> List<ChatMessage>,
    private val fileIngestor: SlackFileIngestor,
) : ChatPlatformAdapter {
    override val botUsername: String = ctx.botUserId
    override val activity: TurnActivityIndicator = SlackActivityIndicator(ctx, threadId.threadTs)

    override fun loadHistory(conversationId: ConversationId): List<ChatMessage> = historyLoader(conversationId)

    override suspend fun postReply(text: String): ReplyResult {
        val postResponse =
            ctx.client().chatPostMessage { req ->
                req.channel(ctx.channelId)
                req.threadTs(threadId.threadTs)

                req.markdownText(text)
            }

        if (!postResponse.isOk) {
            Loggers.SLACK.warn("Slack markdown post failed, fallback to plain text")
            ctx.say(text)
        }

        return ReplyResult(
            messageId = postResponse.ts,
            timestamp = slackTsToMillis(postResponse.ts),
        )
    }

    override fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = fileIngestor.ingest(conversationId, files)
}

fun slackChatPlatformAdapter(
    ctx: EventContext,
    threadId: ChatThreadId,
    currentMessageTs: String,
    fileIngestor: SlackFileIngestor,
): SlackChatPlatformAdapter =
    SlackChatPlatformAdapter(
        ctx = ctx,
        threadId = threadId,
        historyLoader = { sessionId ->
            if (threadId.isStarted) {
                loadThreadHistory(ctx, threadId.threadTs, currentMessageTs, sessionId, fileIngestor)
            } else {
                emptyList()
            }
        },
        fileIngestor = fileIngestor,
    )

private class SlackActivityIndicator(
    ctx: EventContext,
    threadTs: String,
) : TurnActivityIndicator {
    private val ctx = ctx
    private val threadTs = threadTs
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
                Loggers.SLACK.warn("Slack assistant status update failed: {}", response.error)
            }
        }.onFailure {
            Loggers.SLACK.warn("Slack assistant status update failed", it)
        }
    }
}

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
    private val virtualPathsFactory: VirtualPathsFactory,
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
                val virtualPaths = virtualPathsFactory.forConversation(conversationId)
                val localPath = download(virtualPaths, file) ?: return@mapNotNull null
                file.copy(localPath = virtualPaths.virtualPath(localPath.toString()))
            }

    private fun download(
        virtualPaths: VirtualPaths,
        file: IncomingChatFile,
    ): Path? =
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

            Files.createDirectories(virtualPaths.sessionRoot)
            val target = virtualPaths.sessionRoot.resolve(downloadFileName(file))
            Files.write(target, response.body())
            return@runCatching target
        }.getOrElse {
            Loggers.SLACK.warn("Slack file ingest failed for file id={}", file.id, it)
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

internal fun downloadFileName(file: IncomingChatFile): String = "${sanitizeFileName(file.id)}-${sanitizeFileName(file.name)}"

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

private const val MAX_MESSAGE_FILES = 3
