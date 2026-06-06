package com.github.uncomplexco.sidekick.ports.chat

import com.github.uncomplexco.sidekick.application.chat.ReplyResult

fun interface ReplyToMessage {
    suspend fun postReply(text: String): ReplyResult
}

interface ChatActivityIndicator {
    fun start(text: String? = null)

    fun clear()
}

object NoopChatActivityIndicator : ChatActivityIndicator {
    override fun start(text: String?) = Unit

    override fun clear() = Unit
}
