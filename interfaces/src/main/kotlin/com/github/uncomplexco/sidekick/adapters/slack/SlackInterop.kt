package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.ChatFileIngestor
import com.github.uncomplexco.sidekick.adapters.chat.ChatUiRenderer
import com.github.uncomplexco.sidekick.adapters.MAX_MESSAGE_FILES
import com.github.uncomplexco.sidekick.adapters.statusLine
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatThreadId
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.utils.ImageSummarizer
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.model.Attachment
import com.slack.api.model.File as SlackFile
import com.slack.api.model.block.Blocks.asBlocks
import com.slack.api.model.block.Blocks.context
import com.slack.api.model.block.Blocks.markdown
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.composition.BlockCompositions.markdownText
import com.slack.api.util.json.GsonFactory
import java.io.IOException
import java.net.http.HttpClient
import java.time.Duration
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

private fun attachmentUploadFailure(
    name: String,
    error: Throwable,
): String? {
    Loggers.SLACK.warn("Slack reply attachment upload failed for {}", name, error)
    return null
}

class SlackChatPlatformAdapter(
    private val ctx: EventContext,
    threadId: ChatThreadId,
    private val historyLoader: suspend (ConversationId) -> List<ChatMessage>,
    private val fileIngestor: SlackFileIngestor,
    scope: SidekickCoroutineScope,
    renderer: ChatUiRenderer = ChatUiRenderer(),
) : SlackBackedChatPlatformAdapter {
    override val botUsername: String = ctx.botUserId
    private val output = SlackTurnOutput(ctx.client(), ctx.channelId, threadId, scope, renderer)
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        historyLoader(conversationId)

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = fileIngestor.ingest(conversationId, files)
}

internal class SlackTurnOutput(
    private val client: MethodsClient,
    private val channelId: String,
    private val threadId: ChatThreadId?,
    private val scope: SidekickCoroutineScope,
    private val renderer: ChatUiRenderer = ChatUiRenderer(),
) : TurnLifecycle, ResponseDelivery {
    private class Activity(var renewal: Job? = null)

    private val statusLock = Any()
    private var activity: Activity? = null
    private val messages = SlackProgressMessages(client, channelId, threadId?.threadTs, scope)

    override fun begin() {
        synchronized(statusLock) {
            activity?.let { stopActivity(it) }
            activity = Activity()
            messages.begin()
        }
    }

    override fun progress(text: String?) {
        synchronized(statusLock) {
            val current = activity ?: return
            if (threadId == null) return

            if (current.renewal == null) {
                val job = scope.launch {
                    while (isActive) {
                        delay(STATUS_RENEWAL_INTERVAL_MS)
                        synchronized(statusLock) {
                            if (activity === current && isActive) setStatus("processing")
                        }
                    }
                }
                current.renewal = job
                job.invokeOnCompletion {
                    synchronized(statusLock) {
                        if (activity === current) stopActivity(current)
                    }
                }
            }

            if (activity === current && current.renewal?.isActive == true) {
                setStatus("processing")
            }
        }
    }

    override fun end() {
        synchronized(statusLock) {
            activity?.let { stopActivity(it) }
        }
    }

    override suspend fun reportStatus(text: String, key: String, refresh: (() -> String)?) {
        messages.report(text, key, refresh)
    }

    private fun stopActivity(current: Activity) {
        activity = null
        messages.end()
        current.renewal?.cancel()
        setStatus("active")
    }

    override suspend fun markProcessing(message: IncomingMessage) {
        updateReaction(message, PROCESSING_REACTION, add = true)
        progress()
    }

    override suspend fun deliver(
        response: AgentResponse,
        metadata: ResponseMetadata,
    ): PublishedMessage {
        val filePermalinks =
            response.attachments.mapNotNull { attachment ->
                try {
                    val response = client.filesUploadV2 { req ->
                        req.file(attachment.path.toFile())
                        req.filename(attachment.name)
                        req.title(attachment.name)
                    }
                    if (!response.isOk) {
                        Loggers.SLACK.warn(
                            "Slack reply attachment upload failed for {}: {}",
                            attachment.name,
                            response.error,
                        )
                        null
                    } else {
                        response.file?.permalink
                            ?: response.files.orEmpty().firstOrNull()?.permalink
                    }
                } catch (error: IOException) {
                    attachmentUploadFailure(attachment.name, error)
                } catch (error: SlackApiException) {
                    attachmentUploadFailure(attachment.name, error)
                }
            }

        val replyText =
            response.text.takeUnless {
                it.isBlank() || it.contains(SystemPromptBuilder.NO_REPLY_MARKER)
            }
        val text = (listOfNotNull(replyText) + filePermalinks).joinToString("\n")

        if (text.isBlank()) {
            val ts = Clock.System.now().toEpochMilliseconds()
            return PublishedMessage("no_reply:$ts", ts)
        }

        val parts = renderer.render("slack", text)
        val fallbackText = parts.joinToString("") { it.text ?: it.fallbackText }
        val gson = GsonFactory.createSnakeCase()
        val blocks =
            parts.flatMap { part ->
                part.payload?.get("blocks")?.jsonArray?.toList()
                    ?: if (part.text.isNullOrBlank()) emptyList()
                    else
                        Json.parseToJsonElement(gson.toJson(replyBlocks(part.text, null)))
                            .jsonArray
                            .toList()
            } +
                metadata
                    .statusLine()
                    ?.let { status ->
                        Json.parseToJsonElement(
                                gson.toJson(
                                    asBlocks(
                                        context { block ->
                                            block.elements(listOf(markdownText(status)))
                                        }
                                    )
                                )
                            )
                            .jsonArray
                            .toList()
                    }
                    .orEmpty()
        Loggers.SLACK.info("Posting Slack reply in {} with {} blocks", channelId, blocks.size)
        val postResponse = client.chatPostMessage { req ->
            req.channel(channelId)
            messages.threadTs?.also { req.threadTs(it) }
            req.text(fallbackText)
            if (parts.any { it.payload != null }) {
                req.blocksAsString(JsonArray(blocks).toString())
            } else {
                req.blocks(replyBlocks(fallbackText, metadata.statusLine()))
            }
        }

        val response =
            if (postResponse.isOk) {
                postResponse
            } else {
                Loggers.SLACK.warn(
                    "Slack block post failed: {}; fallback to plain text",
                    postResponse.error,
                )
                client.chatPostMessage { req ->
                    req.channel(channelId)
                    messages.threadTs?.also { req.threadTs(it) }
                    req.text(fallbackText)
                }
            }

        return PublishedMessage(response.ts, slackTsToMillis(response.ts))
    }

    override suspend fun markCompleted(message: IncomingMessage) {
        updateReaction(message, PROCESSING_REACTION, add = false)
        updateReaction(message, COMPLETED_REACTION, add = true)
    }

    override suspend fun markFailed(message: IncomingMessage) {
        updateReaction(message, PROCESSING_REACTION, add = false)
    }

    private fun setStatus(status: String) {
        val targetThread = threadId ?: return
        runCatching {
                Loggers.SLACK.info(
                    "Updating Slack agent session status in {} thread {}: {}",
                    channelId,
                    targetThread.threadTs,
                    status,
                )
                val response = client.agentsSessionsSetStatus { req ->
                    req.channelId(channelId)
                    req.threadTs(targetThread.threadTs)
                    req.status(status)
                    req
                }
                if (!response.isOk) {
                    Loggers.SLACK.warn(
                        "Slack agent session status update failed: {}",
                        response.error,
                    )
                }
            }
            .onFailure { Loggers.SLACK.warn("Slack agent session status update failed", it) }
    }

    private fun updateReaction(
        message: IncomingMessage,
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
                    Loggers.SLACK.warn(
                        "Slack processing reaction update failed: {}",
                        response.error,
                    )
                }
            }
            .onFailure { Loggers.SLACK.warn("Slack processing reaction update failed", it) }
    }

    private companion object {
        const val STATUS_RENEWAL_INTERVAL_MS = 8_000L
    }
}

private const val PROCESSING_REACTION = "eyes"
private const val COMPLETED_REACTION = "white_check_mark"

private fun replyBlocks(
    text: String,
    statusLine: String?,
): List<LayoutBlock> =
    asBlocks(markdown { it.text(text) }) +
        statusLine
            ?.let { asBlocks(context { block -> block.elements(listOf(markdownText(it))) }) }
            .orEmpty()

fun slackChatPlatformAdapter(
    ctx: EventContext,
    threadId: ChatThreadId,
    currentMessageTs: String,
    fileIngestor: SlackFileIngestor,
    scope: SidekickCoroutineScope,
    renderer: ChatUiRenderer = ChatUiRenderer(),
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
        scope = scope,
        renderer = renderer,
    )

internal fun incomingChatFiles(
    files: List<SlackFile>?,
    attachments: List<Attachment>?,
): List<IncomingChatFile> {
    val directFiles = files.toIncomingChatFiles()
    return directFiles.ifEmpty {
        attachments.orEmpty().flatMap { it.files.orEmpty() }.toIncomingChatFiles()
    }
}

class SlackFileIngestor(
    slackBotToken: String,
    virtualPathsFactory: VirtualPathsFactory,
    imageSummarizer: ImageSummarizer,
    httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) {
    private val delegate =
        ChatFileIngestor(virtualPathsFactory, imageSummarizer, httpClient, "Bearer $slackBotToken")

    suspend fun ingest(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
        summarizeImages: Boolean = true,
    ): List<IncomingChatFile> = delegate.ingest(conversationId, files, summarizeImages)
}

private fun List<SlackFile>?.toIncomingChatFiles(): List<IncomingChatFile> =
    this.orEmpty().take(MAX_MESSAGE_FILES).mapNotNull { file ->
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

internal fun slackTsToMillis(ts: String): Long = (ts.toDouble().times(1000)).toLong()

internal fun isBotsOwnMessage(
    senderBotId: String?,
    ctx: EventContext,
): Boolean = senderBotId != null && senderBotId == ctx.botUserId

internal fun containsMention(
    text: String,
    username: String,
): Boolean = text.contains("<@$username>")
