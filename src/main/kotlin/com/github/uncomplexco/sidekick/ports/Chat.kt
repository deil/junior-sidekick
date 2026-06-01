package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.sessions.ChatMessage
import com.slack.api.methods.MethodsClient

data class ReplyResult(
    val messageId: String,
    val timestamp: Long,
)

fun interface ReplyToMessage {
    suspend fun postReply(text: String): ReplyResult
}

class ChatPlatformAdapter(
    val botUsername: String,
    val historyLoader: () -> List<ChatMessage>,
    val reply: ReplyToMessage,
    val slackClient: MethodsClient,
)
