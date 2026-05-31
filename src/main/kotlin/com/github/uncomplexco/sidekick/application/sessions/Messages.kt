package com.github.uncomplexco.sidekick.application.sessions

import kotlinx.serialization.Serializable

data class ChatConversationId(
    val channelId: String?,
    val threadId: String? = null,
) {
    val isThread: Boolean = threadId != null
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val sender: String? = null,
    val text: String,
    val timestamp: Long,
)

data class Message(
    val id: String,
)

enum class MessageRole {
    USER,
    ASSISTANT,
}
