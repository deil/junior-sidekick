package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import java.nio.file.Files
import java.nio.file.Path

interface ChatPlatformAdapter {
    val botUsername: String
    val activity: TurnActivityIndicator

    fun loadHistory(conversationId: ConversationId): List<ChatMessage>

    fun markQueued(message: InboundMessage) {
    }

    fun markProcessing(message: InboundMessage) {
    }

    suspend fun postReply(reply: ChatReply): ReplyResult

    fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile>
}

interface SlackBackedChatPlatformAdapter : ChatPlatformAdapter

interface TurnActivityIndicator {
    fun start(text: String? = null)

    fun `continue`(text: String? = null)

    fun toolCall(name: String)

    fun clear()

    fun endTurn()
}

data class ChatThreadId(
    val threadTs: String,
    val isStarted: Boolean,
)

data class ChatConversationId(
    val channelId: String,
    val threadId: String? = null,
) {
    val isDM: Boolean = channelId.startsWith("D")
    val isThread: Boolean = threadId != null

    fun logLabel(): String =
        when {
            isDM && isThread -> "[DM/$threadId]"
            isDM -> "[DM/$channelId]"
            isThread -> "[#$channelId/$threadId]"
            else -> "[#$channelId]"
        }
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

data class ChatReply(
    val text: String,
    val attachments: List<ReplyAttachment> = emptyList(),
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
