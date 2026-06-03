package com.github.uncomplexco.sidekick.application.core

import kotlinx.serialization.Serializable

enum class MessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class MessageAuthor(
    val username: String,
    val fullName: String?,
)
