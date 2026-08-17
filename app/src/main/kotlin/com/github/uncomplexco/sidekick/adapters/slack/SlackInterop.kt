package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.TurnResultHandler
import com.github.uncomplexco.sidekick.application.chat.TurnStats
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Attachment
import com.slack.api.model.block.Blocks.asBlocks
import com.slack.api.model.block.Blocks.context
import com.slack.api.model.block.Blocks.markdown
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.composition.BlockCompositions.markdownText
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import com.slack.api.model.File as SlackFile

class SlackChatPlatformAdapter(
    private val ctx: EventContext,
    threadId: ChatThreadId,
    private val historyLoader: suspend (ConversationId) -> List<ChatMessage>,
    private val fileIngestor: SlackFileIngestor,
) : SlackBackedChatPlatformAdapter {
    override val botUsername: String = ctx.botUserId
    override val resultHandler: TurnResultHandler = SlackTurnResultHandler(ctx.client(), ctx.channelId, threadId)

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> = historyLoader(conversationId)

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = fileIngestor.ingest(conversationId, files)
}

internal class SlackTurnResultHandler(
    private val client: MethodsClient,
    private val channelId: String,
    private val threadId: ChatThreadId?,
) : TurnResultHandler {
    private var turnActive = false

    override fun start() {
        turnActive = true
    }

    override fun `continue`(text: String?) {
        setStatus(
            status = STATUS_THINKING,
            emoji = ":face_in_clouds:",
            loadingMessages = text?.let(::listOf) ?: LOADING_MESSAGES,
        )
    }

    override fun endTurn() {
        turnActive = false
        setStatus("")
    }

    override suspend fun markProcessing(message: InboundMessage) {
        updateReaction(message, PROCESSING_REACTION, add = true)
        `continue`()
    }

    override suspend fun postReply(
        reply: ChatReply,
        stats: TurnStats?,
    ): ReplyResult {
        val filePermalinks =
            reply.attachments.mapNotNull { attachment ->
                try {
                    val response =
                        client.filesUploadV2 { req ->
                            req.file(attachment.path.toFile())
                            req.filename(attachment.name)
                            req.title(attachment.name)
                        }
                    if (!response.isOk) {
                        Loggers.SLACK.warn("Slack reply attachment upload failed for {}: {}", attachment.name, response.error)
                        null
                    } else {
                        response.file?.permalink ?: response.files
                            .orEmpty()
                            .firstOrNull()
                            ?.permalink
                    }
                } catch (error: Exception) {
                    Loggers.SLACK.warn("Slack reply attachment upload failed for {}", attachment.name, error)
                    null
                }
            }
        val text = (listOf(reply.text) + filePermalinks).joinToString("\n")

        val postResponse =
            client.chatPostMessage { req ->
                req.channel(channelId)
                threadId?.also { req.threadTs(it.threadTs) }
                req.text(text)
                req.blocks(replyBlocks(text, reply.statusLine ?: stats?.statusLine()))
            }

        val response =
            if (postResponse.isOk) {
                postResponse
            } else {
                Loggers.SLACK.warn("Slack block post failed: {}; fallback to plain text", postResponse.error)
                client.chatPostMessage { req ->
                    req.channel(channelId)
                    threadId?.also { req.threadTs(it.threadTs) }
                    req.text(text)
                }
            }

        return ReplyResult(response.ts, slackTsToMillis(response.ts))
    }

    override suspend fun markCompleted(message: InboundMessage) {
        updateReaction(message, PROCESSING_REACTION, add = false)
        updateReaction(message, COMPLETED_REACTION, add = true)
    }

    override suspend fun markFailed(message: InboundMessage) {
        updateReaction(message, PROCESSING_REACTION, add = false)
    }

    private fun replyBlocks(
        text: String,
        statusLine: String?,
    ): List<LayoutBlock> =
        asBlocks(markdown { it.text(text) }) +
            statusLine?.let { asBlocks(context { block -> block.elements(listOf(markdownText(it))) }) }.orEmpty()

    private fun TurnStats.statusLine(): String =
        listOfNotNull(
            profileName,
            formattedExecutionTime(),
            "${formattedTokenCount(inputTokenCount)} → ${formattedTokenCount(outputTokenCount)}",
            toolCallCount.takeIf { it > 0 }?.let { "$it tools" },
        ).joinToString(" · ")

    private fun TurnStats.formattedExecutionTime(): String =
        if (executionTimeSeconds < 60) {
            "${executionTimeSeconds}s"
        } else {
            "${executionTimeSeconds / 60}m ${executionTimeSeconds % 60}s"
        }

    private fun formattedTokenCount(tokenCount: Long): String =
        if (tokenCount < 1_000) {
            tokenCount.toString()
        } else {
            String.format(Locale.ROOT, "%.1fK", tokenCount / 1000.0)
        }

    private fun setStatus(
        status: String,
        emoji: String? = null,
        loadingMessages: List<String>? = null,
    ) {
        val targetThread = threadId ?: return
        runCatching {
            val response =
                client.assistantThreadsSetStatus { req ->
                    req.channelId(channelId)
                    req.threadTs(targetThread.threadTs)
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

    private fun updateReaction(
        message: InboundMessage,
        reaction: String,
        add: Boolean,
    ) {
        runCatching {
            val response =
                if (add) {
                    client.reactionsAdd { req ->
                        req.channel(channelId)
                        req.timestamp(message.id)
                        req.name(reaction)
                    }
                } else {
                    client.reactionsRemove { req ->
                        req.channel(channelId)
                        req.timestamp(message.id)
                        req.name(reaction)
                    }
                }

            if (!response.isOk) {
                Loggers.SLACK.warn("Slack processing reaction update failed: {}", response.error)
            }
        }.onFailure {
            Loggers.SLACK.warn("Slack processing reaction update failed", it)
        }
    }

    private companion object {
        const val STATUS_THINKING = "thinking..."
        val LOADING_MESSAGES =
            listOf(
                "prodding the web for clues...",
                "negotiating with the documentation...",
                "looking for the mildly sensible route...",
                "acting like this was the plan...",
                "asking the APIs to cooperate...",
                "following the breadcrumbs optimistically...",
                "checking what the docs meant to say...",
                "rearranging tokens with confidence...",
                "consulting several tabs at once...",
                "making it look intentional...",
            )
    }
}

private const val PROCESSING_REACTION = "eyes"
private const val COMPLETED_REACTION = "white_check_mark"

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
    private val imageSummarizer: ImageSummarizer,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) {
    suspend fun ingest(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
        summarizeImages: Boolean = true,
    ): List<IncomingChatFile> =
        files
            .take(MAX_MESSAGE_FILES)
            .mapNotNull { file ->
                val virtualPaths = virtualPathsFactory.forConversation(conversationId)
                val localPath = download(virtualPaths, file) ?: return@mapNotNull null
                file.copy(
                    localPath = virtualPaths.virtualPath(localPath.toString()),
                    summary =
                        if (summarizeImages && file.mimetype?.startsWith("image/") == true) {
                            when (val result = imageSummarizer.summarize(localPath)) {
                                is ImageSummarizer.Result.Success -> {
                                    result.summary
                                }

                                is ImageSummarizer.Result.Failure -> {
                                    Loggers.SLACK.warn("Image summarization failed for file id={}", file.id, result.error)
                                    null
                                }
                            }
                        } else {
                            null
                        },
                )
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
