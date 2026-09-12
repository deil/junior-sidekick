package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.chat.DiscordBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.stats.TurnStats
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import java.util.Locale
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DiscordTurnOutput::class.java)

internal data class DiscordSentMessage(
    val id: String,
    val createdAtMs: Long,
)

internal class DiscordChatPlatformAdapter(
    override val botUsername: String,
    updateReaction: suspend (String, Boolean) -> Unit = { _, _ -> },
    startTyping: () -> AutoCloseable,
    private val loadChannelHistory: suspend (Int, String?) -> DiscordChannelHistoryPage,
    private val loadThreadHistory: suspend (String, Int, String?) -> DiscordThreadHistoryPage,
    send: suspend (String) -> DiscordSentMessage,
) : DiscordBackedChatPlatformAdapter {
    private val output = DiscordTurnOutput(updateReaction, startTyping, send)
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
    ): List<IncomingChatFile> {
        require(files.isEmpty()) { "Discord attachments must be rejected at ingress" }
        return emptyList()
    }
}

internal class DiscordTurnOutput(
    private val updateReaction: suspend (String, Boolean) -> Unit = { _, _ -> },
    private val startTyping: () -> AutoCloseable = { AutoCloseable {} },
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
        updateReaction(PROCESSING_REACTION, add = true)
        progress()
    }

    override suspend fun markCompleted(message: IncomingMessage) {
        updateReaction(PROCESSING_REACTION, add = false)
        updateReaction(COMPLETED_REACTION, add = true)
    }

    override suspend fun markFailed(message: IncomingMessage) {
        updateReaction(PROCESSING_REACTION, add = false)
    }

    private suspend fun updateReaction(
        emoji: String,
        add: Boolean,
    ) {
        runCatching { updateReaction.invoke(emoji, add) }
            .onFailure { log.warn("Discord processing reaction update failed", it) }
    }

    override suspend fun deliver(
        response: AgentResponse,
        metadata: ResponseMetadata,
    ): PublishedMessage {
        val attachmentNotice =
            response.attachments
                .takeIf { it.isNotEmpty() }
                ?.joinToString(
                    prefix = "\n\nGenerated attachments are not supported on Discord: "
                ) {
                    it.name
                }
                .orEmpty()
        val replyText =
            response.text.takeUnless {
                it.isBlank() || it.contains(SystemPromptBuilder.NO_REPLY_MARKER)
            }
        val text = replyText.orEmpty() + attachmentNotice

        if (text.isBlank()) {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            return PublishedMessage("no_reply:$timestamp", timestamp)
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

    private fun TurnStats.statusLine(): String =
        listOfNotNull(
                profileName,
                formattedExecutionTime(),
                "${formattedTokenCount(inputTokenCount)} → ${formattedTokenCount(outputTokenCount)}",
                toolCallCount.takeIf { it > 0 }?.let { "$it tools" },
            )
            .joinToString(" · ")

    private fun ResponseMetadata.statusLine(): String? =
        when (this) {
            ResponseMetadata.None -> null
            ResponseMetadata.RuntimeFailure -> "`[runtime failure]`"
            is ResponseMetadata.Turn -> stats.statusLine()
        }

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
