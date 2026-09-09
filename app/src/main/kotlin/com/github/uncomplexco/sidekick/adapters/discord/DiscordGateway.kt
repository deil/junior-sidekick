package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatConversationKind
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryPage
import com.github.uncomplexco.sidekick.application.chat.DiscordThreadHistoryPage
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.usecases.HandleIncomingChatMessageUsecase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.LinkedHashSet

internal data class DiscordIncomingMessage(
    val id: String,
    val channelId: String,
    val threadId: String? = null,
    val createdAtMs: Long,
    val authorId: String,
    val authorName: String,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val kind: DiscordMessageKind,
    val isBot: Boolean = false,
    val isWebhook: Boolean = false,
)

internal enum class DiscordMessageKind {
    DIRECT_MESSAGE,
    GUILD_MENTION,
    GUILD_THREAD_MESSAGE,
    IGNORED_GUILD_MESSAGE,
}

internal fun discordMessageKind(
    isFromGuild: Boolean,
    isThread: Boolean,
    contentRaw: String,
    botId: String,
): DiscordMessageKind =
    when {
        !isFromGuild -> DiscordMessageKind.DIRECT_MESSAGE
        contentRaw.contains("<@$botId>") || contentRaw.contains("<@!$botId>") -> DiscordMessageKind.GUILD_MENTION
        isThread -> DiscordMessageKind.GUILD_THREAD_MESSAGE
        else -> DiscordMessageKind.IGNORED_GUILD_MESSAGE
    }

internal class DiscordIngress(
    private val handle: suspend (ChatConversationId, InboundMessage, ChatPlatformAdapter) -> Unit,
) {
    private val handledMessageIds = LinkedHashSet<String>()

    suspend fun receive(
        message: DiscordIncomingMessage,
        botUsername: String,
        send: suspend (String) -> DiscordSentMessage,
    ) = receive(
        message = message,
        botUsername = botUsername,
        send = send,
        updateReaction = { _, _ -> },
        startTyping = { AutoCloseable {} },
        loadChannelHistory = { _, _ -> DiscordChannelHistoryPage(message.channelId, emptyList(), null) },
        loadThreadHistory = { threadId, _, _ -> DiscordThreadHistoryPage(message.channelId, threadId, emptyList(), null) },
    )

    suspend fun receive(
        message: DiscordIncomingMessage,
        botUsername: String,
        send: suspend (String) -> DiscordSentMessage,
        updateReaction: suspend (String, Boolean) -> Unit,
        startTyping: () -> AutoCloseable,
        loadChannelHistory: suspend (Int, String?) -> DiscordChannelHistoryPage,
        loadThreadHistory: suspend (String, Int, String?) -> DiscordThreadHistoryPage,
    ) {
        if (message.kind == DiscordMessageKind.IGNORED_GUILD_MESSAGE) return
        if (message.isBot || message.isWebhook || !admit(message.id)) return
        if (message.attachmentNames.isNotEmpty()) {
            send("Attachments are not supported yet. Send a text-only message.")
            return
        }
        val text = message.text.trim()
        if (text.isEmpty()) return

        handle(
            ChatConversationId(
                channelId = message.channelId,
                threadId = message.threadId,
                platform = ChatPlatform.DISCORD,
                kind =
                    when (message.kind) {
                        DiscordMessageKind.DIRECT_MESSAGE -> ChatConversationKind.DIRECT_MESSAGE
                        DiscordMessageKind.GUILD_MENTION -> ChatConversationKind.CHANNEL
                        DiscordMessageKind.GUILD_THREAD_MESSAGE -> ChatConversationKind.CHANNEL
                        DiscordMessageKind.IGNORED_GUILD_MESSAGE -> error("Ignored guild messages must not reach mapping")
                    },
            ),
            InboundMessage(
                id = message.id,
                createdAtMs = message.createdAtMs,
                sender = MessageAuthor(message.authorId, message.authorName),
                text = text,
                type =
                    when (message.kind) {
                        DiscordMessageKind.DIRECT_MESSAGE -> ChatMessageType.ASSISTANT_MESSAGE
                        DiscordMessageKind.GUILD_MENTION -> ChatMessageType.EXPLICIT_MENTION
                        DiscordMessageKind.GUILD_THREAD_MESSAGE -> ChatMessageType.PASSIVE_MESSAGE
                        DiscordMessageKind.IGNORED_GUILD_MESSAGE -> error("Ignored guild messages must not reach mapping")
                    },
            ),
            DiscordChatPlatformAdapter(botUsername, updateReaction, startTyping, loadChannelHistory, loadThreadHistory, send),
        )
    }

    private fun admit(messageId: String): Boolean =
        synchronized(handledMessageIds) {
            if (!handledMessageIds.add(messageId)) return@synchronized false
            if (handledMessageIds.size > MAX_HANDLED_MESSAGES) {
                handledMessageIds.remove(handledMessageIds.first())
            }
            true
        }

    private companion object {
        const val MAX_HANDLED_MESSAGES = 1_024
    }
}

internal suspend fun loadDiscordChannelHistory(
    channelId: String,
    limit: Int,
    cursor: String?,
    loadPage: suspend (String?, Int) -> List<DiscordChannelHistoryMessage>,
): DiscordChannelHistoryPage {
    val loaded = mutableListOf<DiscordChannelHistoryMessage>()
    var before = cursor
    var exhausted = false
    while (loaded.size <= limit && !exhausted) {
        val pageSize = minOf(100, limit + 1 - loaded.size)
        val page = loadPage(before, pageSize)
        loaded += page
        before = page.lastOrNull()?.id
        exhausted = page.size < pageSize
    }

    val messages = loaded.take(limit)
    return DiscordChannelHistoryPage(
        channelId = channelId,
        messages = messages,
        nextCursor = messages.lastOrNull()?.id?.takeIf { loaded.size > limit },
    )
}

@Configuration
@ConditionalOnProperty(prefix = "adapters.chat", name = ["platform"], havingValue = "discord")
class DiscordConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    fun discordGateway(
        @Value("\${adapters.discord.bot.token}") token: String,
        selectedChatPlatform: ChatPlatform,
        incoming: HandleIncomingChatMessageUsecase,
        scope: SidekickCoroutineScope,
    ): JDA {
        check(selectedChatPlatform == ChatPlatform.DISCORD)
        val ingress = DiscordIngress(incoming::handle)
        val listener =
            object : ListenerAdapter() {
                override fun onMessageReceived(event: MessageReceivedEvent) {
                    var replyChannel: MessageChannel = event.channel
                    val historyChannel: MessageChannel =
                        (event.channel as? ThreadChannel)?.parentMessageChannel ?: event.channel
                    var needsThread = event.isFromGuild && event.channel !is ThreadChannel
                    val replyChannelMutex = Mutex()
                    val resolveReplyChannel: suspend () -> MessageChannel = {
                        replyChannelMutex.withLock {
                            if (needsThread) {
                                replyChannel =
                                    withContext(Dispatchers.IO) {
                                        event.message.startedThread
                                            ?: event.message.createThreadChannel(event.jda.selfUser.name).complete()
                                    }
                                needsThread = false
                            }
                            replyChannel
                        }
                    }
                    val send: suspend (String) -> DiscordSentMessage = { text ->
                        resolveReplyChannel().send(text)
                    }
                    val startTyping = {
                        startDiscordTypingHeartbeat(
                            scope = scope,
                            sendTyping = {
                                val channel = resolveReplyChannel()
                                withContext(Dispatchers.IO) { channel.sendTyping().complete() }
                            },
                        )
                    }
                    val updateReaction: suspend (String, Boolean) -> Unit = { emoji, add ->
                        withContext(Dispatchers.IO) {
                            val reaction = Emoji.fromUnicode(emoji)
                            if (add) {
                                event.message.addReaction(reaction).complete()
                            } else {
                                event.message.removeReaction(reaction).complete()
                            }
                        }
                    }
                    val loadChannelHistory: suspend (Int, String?) -> DiscordChannelHistoryPage = { limit, cursor ->
                        historyChannel.loadDiscordHistoryPage(historyChannel.id, limit, cursor)
                    }
                    val loadThreadHistory: suspend (String, Int, String?) -> DiscordThreadHistoryPage =
                        { threadId, limit, cursor ->
                            val thread = requireNotNull(event.jda.getThreadChannelById(threadId)) {
                                "Discord thread $threadId is not active or visible."
                            }
                            require(thread.parentChannel.id == historyChannel.id) {
                                "Discord thread $threadId is not in channel ${historyChannel.id}."
                            }
                            thread.loadDiscordThreadHistory(historyChannel.id, limit, cursor)
                        }
                    scope.launch {
                        runCatching {
                            ingress.receive(
                                event.toIncomingMessage(),
                                event.jda.selfUser.id,
                                send,
                                updateReaction,
                                startTyping,
                                loadChannelHistory,
                                loadThreadHistory,
                            )
                        }
                            .onFailure { log.error("Discord message handling failed", it) }
                    }
                }
            }
        val jda =
            JDABuilder
                .createLight(token, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(listener)
                .build()
                .awaitReady()
        return jda
    }

    private fun MessageReceivedEvent.toIncomingMessage(): DiscordIncomingMessage {
        val thread = channel as? ThreadChannel
        return DiscordIncomingMessage(
            id = message.id,
            channelId = thread?.parentMessageChannel?.id ?: channel.id,
            threadId = thread?.id,
            createdAtMs = message.timeCreated.toInstant().toEpochMilli(),
            authorId = author.id,
            authorName = author.globalName ?: author.name,
            text = message.contentRaw,
            attachmentNames = message.attachments.map(Message.Attachment::getFileName),
            kind = discordMessageKind(isFromGuild, thread != null, message.contentRaw, jda.selfUser.id),
            isBot = author.isBot,
            isWebhook = message.isWebhookMessage,
        )
    }

    private suspend fun net.dv8tion.jda.api.entities.channel.middleman.MessageChannel.send(text: String): DiscordSentMessage {
        val message = withContext(Dispatchers.IO) { sendMessage(text).setAllowedMentions(emptyList()).complete() }
        return DiscordSentMessage(message.id, message.timeCreated.toInstant().toEpochMilli())
    }

    private suspend fun MessageChannel.loadDiscordHistoryPage(
        resultChannelId: String,
        limit: Int,
        cursor: String?,
    ): DiscordChannelHistoryPage =
        loadDiscordChannelHistory(resultChannelId, limit, cursor) { before, pageSize ->
            withContext(Dispatchers.IO) {
                val messages =
                    if (before == null) {
                        history.retrievePast(pageSize).complete()
                    } else {
                        getHistoryBefore(before, pageSize).complete().retrievedHistory
                    }
                messages.map { it.toDiscordChannelHistoryMessage() }
            }
        }

    private suspend fun ThreadChannel.loadDiscordThreadHistory(
        parentChannelId: String,
        limit: Int,
        cursor: String?,
    ): DiscordThreadHistoryPage {
        val page = loadDiscordHistoryPage(parentChannelId, limit, cursor)
        return DiscordThreadHistoryPage(page.channelId, id, page.messages, page.nextCursor)
    }

    private fun Message.toDiscordChannelHistoryMessage(): DiscordChannelHistoryMessage =
        DiscordChannelHistoryMessage(
            id = id,
            sentAt = timeCreated.toInstant().toString(),
            userId = author.id,
            username = author.globalName ?: author.name,
            isBot = author.isBot,
            text = contentRaw,
            threadId = startedThread?.id,
        )

    private companion object {
        val log = LoggerFactory.getLogger(DiscordConfiguration::class.java)
    }
}
