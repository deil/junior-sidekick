package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.ports.chat.ChatActivityIndicator
import com.github.uncomplexco.sidekick.ports.chat.ReplyToMessage
import com.slack.api.methods.MethodsClient

data class ReplyResult(
    val messageId: String,
    val timestamp: Long,
)

fun interface ChatFileIngestor {
    fun ingest(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile>
}

class ChatPlatformAdapter(
    val botUsername: String,
    val historyLoader: (ConversationId) -> List<ChatMessage>,
    val reply: ReplyToMessage,
    val activity: ChatActivityIndicator,
    val fileIngestor: ChatFileIngestor,
)

interface SlackClientProvider {
    fun client(): MethodsClient

    fun hasToken(): Boolean
}

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
