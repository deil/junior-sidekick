package com.github.uncomplexco.sidekick.application.sessions

import java.nio.file.Path

data class SessionId(
    val channelId: String,
    val threadId: String,
) {
    fun folder(stateRoot: Path): Path {
        val conversationFolder = sanitizePathSegment(channelId)
        return if (threadId.isNullOrBlank()) {
            stateRoot.resolve("slack/channels").resolve(conversationFolder).resolve("session")
        } else {
            stateRoot
                .resolve("slack/channels")
                .resolve(conversationFolder)
                .resolve("threads")
                .resolve(sanitizePathSegment(threadId))
        }
    }

    fun lockKey(): String =
        buildString {
            append(channelId)
            append(':')
            append(threadId ?: "session")
        }
}

private fun sanitizePathSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

fun ChatConversationId.toSessionId() = SessionId(channelId!!, threadId!!)
