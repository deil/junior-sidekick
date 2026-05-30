package com.github.uncomplexco.sidekick.application.conversations

import kotlinx.serialization.Serializable

enum class MessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class Message(
    val id: String,
    val role: MessageRole,
    val sender: String? = null,
    val text: String,
    val timestamp: Long,
)
