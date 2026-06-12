package com.github.uncomplexco.sidekick.ports.chat

import com.github.uncomplexco.sidekick.application.chat.ReplyResult

fun interface ReplyToMessage {
    suspend fun postReply(text: String): ReplyResult
}

interface ChatActivityIndicator {
    fun start(text: String? = null)

    fun `continue`(text: String? = null)

    fun toolCall(name: String)

    fun clear()

    fun endTurn()
}
