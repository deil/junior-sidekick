package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery

interface ChatPlatformAdapter {
    val botUsername: String
    val lifecycle: TurnLifecycle
    val delivery: ResponseDelivery

    suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage>

    suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile>
}

interface SlackBackedChatPlatformAdapter : ChatPlatformAdapter

interface DiscordBackedChatPlatformAdapter : ChatPlatformAdapter {
    suspend fun loadChannelHistory(
        limit: Int,
        cursor: String?,
    ): DiscordChannelHistoryPage

    suspend fun loadThreadHistory(
        threadId: String,
        limit: Int,
        cursor: String?,
    ): DiscordThreadHistoryPage
}

data class DiscordChannelHistoryPage(
    val channelId: String,
    val messages: List<DiscordChannelHistoryMessage>,
    val nextCursor: String?,
)

data class DiscordThreadHistoryPage(
    val channelId: String,
    val threadId: String,
    val messages: List<DiscordChannelHistoryMessage>,
    val nextCursor: String?,
)

data class DiscordChannelHistoryMessage(
    val id: String,
    val sentAt: String,
    val userId: String,
    val username: String,
    val isBot: Boolean,
    val text: String,
    val threadId: String?,
)

interface TurnLifecycle {
    fun begin()

    fun progress(text: String? = null)

    fun end()

    suspend fun markProcessing(message: IncomingMessage)

    suspend fun markCompleted(message: IncomingMessage)

    suspend fun markFailed(message: IncomingMessage)
}

data class ChatThreadId(
    val threadTs: String,
    val isStarted: Boolean,
)

data class ChatConversationId(
    val channelId: String,
    val threadId: String? = null,
    val platform: ChatPlatform = ChatPlatform.SLACK,
    val kind: ChatConversationKind =
        if (channelId.startsWith("D")) ChatConversationKind.DIRECT_MESSAGE
        else ChatConversationKind.CHANNEL,
) {
    val isDM: Boolean = kind == ChatConversationKind.DIRECT_MESSAGE
    val isThread: Boolean = threadId != null

    fun logLabel(): String =
        when {
            isDM && isThread -> "[DM/$threadId]"
            isDM -> "[DM/$channelId]"
            isThread -> "[#$channelId/$threadId]"
            else -> "[#$channelId]"
        }
}

enum class ChatPlatform {
    SLACK,
    DISCORD,
}

enum class ChatConversationKind {
    CHANNEL,
    DIRECT_MESSAGE,
}

data class ChatMessage(
    val id: String,
    val role: SessionMessageRole,
    val author: MessageAuthor?,
    val text: String,
    val timestamp: Long,
    val files: List<IncomingChatFile>,
)

enum class IncomingMessageType {
    EXPLICIT_MENTION,
    PASSIVE_MESSAGE,
    ASSISTANT_MESSAGE,
}

data class PublishedMessage(
    val messageId: String,
    val timestamp: Long,
)

data class IncomingBatch(
    val conversationId: ChatConversationId,
    val messages: List<IncomingMessage>,
)
