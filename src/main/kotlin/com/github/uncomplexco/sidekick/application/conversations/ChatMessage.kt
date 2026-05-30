package com.github.uncomplexco.sidekick.application.conversations

import kotlinx.serialization.Serializable

enum class MessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val sender: String? = null,
    val text: String,
    val timestamp: Long,
)

data class ChatConversationId(
    val channelId: String?,
    val threadId: String? = null,
) {
    val isChannel: Boolean = channelId != null
    val isThread: Boolean = threadId != null
}
