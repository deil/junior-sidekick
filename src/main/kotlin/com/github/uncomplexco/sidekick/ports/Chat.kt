package com.github.uncomplexco.sidekick.ports

data class ReplyResult(
    val messageId: String,
    val timestamp: Long,
)

fun interface ReplyToMessage {
    suspend fun postReply(text: String): ReplyResult
}
