package com.github.uncomplexco.sidekick.ports

fun interface ReplyToMessage {
    suspend fun postReply(text: String)
}
