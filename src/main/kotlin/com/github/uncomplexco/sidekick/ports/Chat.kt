package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.sessions.ChatMessage

data class ReplyResult(
    val messageId: String,
    val timestamp: Long,
)

fun interface ReplyToMessage {
    suspend fun postReply(text: String): ReplyResult
}

class ChatPlatformAdapter(
    val historyLoader: () -> List<ChatMessage>,
    val reply: ReplyToMessage,
)
