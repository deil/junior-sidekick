package com.github.uncomplexco.sidekick.application.conversation

import kotlinx.serialization.Serializable

enum class SessionMessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class MessageAuthor(
    val username: String,
    val fullName: String?,
)
