package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.MessageAuthor
import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.session.IncomingChatFile
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.slack.api.methods.MethodsClient

data class ReplyResult(
    val messageId: String,
    val timestamp: Long,
)

fun interface ReplyToMessage {
    suspend fun postReply(text: String): ReplyResult
}

interface ChatActivityIndicator {
    fun start()

    fun clear()
}

object NoopChatActivityIndicator : ChatActivityIndicator {
    override fun start() = Unit

    override fun clear() = Unit
}

fun interface ChatFileIngestor {
    fun ingest(
        sessionId: SessionId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile>
}

class ChatPlatformAdapter(
    val botUsername: String,
    val historyLoader: (SessionId) -> List<ChatMessage>,
    val reply: ReplyToMessage,
    val activity: ChatActivityIndicator = NoopChatActivityIndicator,
    val fileIngestor: ChatFileIngestor,
)

interface SlackClientProvider {
    fun client(): MethodsClient

    fun hasToken(): Boolean
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val author: MessageAuthor?,
    val text: String,
    val timestamp: Long,
    val files: List<IncomingChatFile>,
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
