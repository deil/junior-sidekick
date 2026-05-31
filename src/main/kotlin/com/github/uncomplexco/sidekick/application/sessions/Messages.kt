package com.github.uncomplexco.sidekick.application.sessions

import kotlinx.serialization.Serializable

data class ChatConversationId(
    val channelId: String?,
    val threadId: String? = null,
) {
    val isThread: Boolean = threadId != null
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val author: MessageAuthor?,
    val text: String,
    val timestamp: Long,
)

enum class MessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class MessageAuthor(
    val username: String,
    val fullName: String?,
)
