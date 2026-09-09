package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import java.nio.file.Files
import java.nio.file.Path

interface ChatPlatformAdapter {
    val botUsername: String
    val resultHandler: TurnResultHandler

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

interface TurnResultHandler {
    fun start()

    fun `continue`(text: String? = null)

    fun endTurn()

    suspend fun markProcessing(message: InboundMessage)

    suspend fun postReply(
        reply: ChatReply,
        stats: TurnStats? = null,
    ): ReplyResult

    suspend fun postRuntimeFailure(error: Exception) {
        postReply(
            ChatReply(
                text = error.message?.takeIf { it.isNotBlank() }?.let { ":warning: $it" } ?: TEMPORARY_FAILURE_REPLY,
                statusLine = RUNTIME_FAILURE_STATUS,
            ),
        )
    }

    suspend fun postUnsubscribed() {
        postReply(ChatReply(UNSUBSCRIBE_ACK))
    }

    suspend fun markCompleted(message: InboundMessage)

    suspend fun markFailed(message: InboundMessage)

    private companion object {
        const val UNSUBSCRIBE_ACK = "Unsubscribed. Mention me to resume"
        const val TEMPORARY_FAILURE_REPLY = ":warning: I hit a temporary model/provider error while processing this. Please retry"
        const val RUNTIME_FAILURE_STATUS = "`[runtime failure]`"
    }
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
        if (channelId.startsWith("D")) ChatConversationKind.DIRECT_MESSAGE else ChatConversationKind.CHANNEL,
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

enum class ChatMessageType {
    EXPLICIT_MENTION,
    PASSIVE_MESSAGE,
    ASSISTANT_MESSAGE,
}

data class ReplyResult(
    val messageId: String,
    val timestamp: Long,
)

data class TurnStats(
    val profileName: String,
    val executionTimeSeconds: Long,
    val toolCallCount: Int,
    val inputTokenCount: Long,
    val outputTokenCount: Long,
)

data class ChatReply(
    val text: String,
    val attachments: List<ReplyAttachment> = emptyList(),
    val statusLine: String? = null,
) {
    fun deleteAttachments() {
        attachments.forEach { Files.deleteIfExists(it.path) }
    }
}

data class ReplyAttachment(
    val path: Path,
    val name: String,
    val mimeType: String,
    val bytes: Long,
)
