package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.adapters.ChatFileIngestor
import com.github.uncomplexco.sidekick.adapters.statusLine
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.DiscordBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachment
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import java.io.IOException
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DiscordTurnOutput::class.java)

internal data class DiscordSentMessage(
    val id: String,
    val createdAtMs: Long,
)

internal class DiscordChatPlatformAdapter(
    override val botUsername: String,
    updateReaction: suspend (String, String, Boolean) -> Unit = { _, _, _ -> },
    startTyping: () -> AutoCloseable,
    private val loadChannelHistory: suspend (Int, String?) -> DiscordChannelHistoryPage,
    private val loadThreadHistory: suspend (String, Int, String?) -> DiscordThreadHistoryPage,
    private val fileIngestor: ChatFileIngestor,
    upload: suspend (ReplyAttachment) -> DiscordSentMessage,
    send: suspend (String) -> DiscordSentMessage,
) : DiscordBackedChatPlatformAdapter {
    private val output = DiscordTurnOutput(updateReaction, startTyping, upload, send)
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        emptyList()

    override suspend fun loadChannelHistory(
        limit: Int,
        cursor: String?,
    ): DiscordChannelHistoryPage = loadChannelHistory.invoke(limit, cursor)

    override suspend fun loadThreadHistory(
        threadId: String,
        limit: Int,
        cursor: String?,
    ): DiscordThreadHistoryPage = loadThreadHistory.invoke(threadId, limit, cursor)

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = fileIngestor.ingest(conversationId, files)
}

internal class DiscordTurnOutput(
    private val updateReaction: suspend (String, String, Boolean) -> Unit = { _, _, _ -> },
    private val startTyping: () -> AutoCloseable = { AutoCloseable {} },
    private val upload: suspend (ReplyAttachment) -> DiscordSentMessage = {
        error("File upload is not configured")
    },
    private val send: suspend (String) -> DiscordSentMessage,
) : TurnLifecycle, ResponseDelivery {
    private var turnActive = false
    private var typingHeartbeat: AutoCloseable? = null

    override fun begin() {
        turnActive = true
    }

    override fun progress(text: String?) {
        if (turnActive && typingHeartbeat == null) {
            typingHeartbeat = startTyping()
        }
    }

    override fun end() {
        turnActive = false
        typingHeartbeat?.close()
        typingHeartbeat = null
    }

    override suspend fun markProcessing(message: IncomingMessage) {
        updateReaction(message.id, PROCESSING_REACTION, add = true)
        progress()
    }

    override suspend fun markCompleted(message: IncomingMessage) {
        updateReaction(message.id, PROCESSING_REACTION, add = false)
        updateReaction(message.id, COMPLETED_REACTION, add = true)
    }

    override suspend fun markFailed(message: IncomingMessage) {
        updateReaction(message.id, PROCESSING_REACTION, add = false)
    }

    private suspend fun updateReaction(
        messageId: String,
        emoji: String,
        add: Boolean,
    ) {
        runCatching { updateReaction.invoke(messageId, emoji, add) }
            .onFailure { log.warn("Discord processing reaction update failed", it) }
    }

    override suspend fun deliver(
        response: AgentResponse,
        metadata: ResponseMetadata,
    ): PublishedMessage {
        val uploaded =
            response.attachments.mapNotNull { attachment ->
                try {
                    log.info("Uploading Discord reply attachment name={}", attachment.name)
                    upload(attachment)
                } catch (error: IOException) {
                    log.warn(
                        "Discord reply attachment upload failed for {}",
                        attachment.name,
                        error,
                    )
                    null
                } catch (error: ErrorResponseException) {
                    log.warn(
                        "Discord reply attachment upload failed for {}",
                        attachment.name,
                        error,
                    )
                    null
                }
            }
        val replyText =
            response.text.takeUnless {
                it.isBlank() || it.contains(SystemPromptBuilder.NO_REPLY_MARKER)
            }
        val text = replyText.orEmpty()

        if (text.isBlank()) {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            return uploaded.lastOrNull()?.let { PublishedMessage(it.id, it.createdAtMs) }
                ?: PublishedMessage("no_reply:$timestamp", timestamp)
        }

        val statusSubtext =
            metadata
                .statusLine()
                ?.takeIf { it.isNotBlank() }
                ?.lineSequence()
                ?.joinToString(prefix = "\n\n", separator = "\n") { "-# $it" }
                .orEmpty()
        val chunks = splitDiscordMessage(text + statusSubtext)

        val sent = chunks.map { send(it) }.last()
        return PublishedMessage(sent.id, sent.createdAtMs)
    }

    private companion object {
        const val PROCESSING_REACTION = "👀"
        const val COMPLETED_REACTION = "✅"
    }
}

internal fun startDiscordTypingHeartbeat(
    scope: SidekickCoroutineScope,
    sendTyping: suspend () -> Unit,
    waitForRefresh: suspend () -> Unit = { delay(discordTypingRefreshIntervalMs()) },
): AutoCloseable {
    val job = scope.launch {
        while (true) {
            try {
                sendTyping()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.warn("Discord typing indicator update failed", error)
            }
            waitForRefresh()
        }
    }
    return AutoCloseable { job.cancel() }
}

internal fun discordTypingRefreshIntervalMs(): Long = DISCORD_TYPING_REFRESH_MS

internal fun splitDiscordMessage(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + DISCORD_MESSAGE_LIMIT, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

private const val DISCORD_MESSAGE_LIMIT = 2_000
private const val DISCORD_TYPING_INDICATOR_LIFETIME_MS = 10_000L
private const val DISCORD_TYPING_REFRESH_MARGIN_MS = 2_000L
private const val DISCORD_TYPING_REFRESH_MS =
    DISCORD_TYPING_INDICATOR_LIFETIME_MS - DISCORD_TYPING_REFRESH_MARGIN_MS
