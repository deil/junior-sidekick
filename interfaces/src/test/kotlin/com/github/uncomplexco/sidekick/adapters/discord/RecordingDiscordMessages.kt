package com.github.uncomplexco.sidekick.adapters.discord

import java.io.IOException

internal class RecordingDiscordMessages {
    val messages = linkedMapOf<String, String>()
    val edits = mutableListOf<Pair<String, String>>()
    var failSend = false
    var failEdit = false

    suspend fun send(text: String): DiscordSentMessage {
        if (failSend) throw IOException("Send failed")
        val id = "message-${messages.size + 1}"
        messages[id] = text
        return DiscordSentMessage(id, 1_000L)
    }

    suspend fun edit(messageId: String, text: String) {
        if (failEdit) throw IOException("Edit failed")
        check(messageId in messages)
        messages[messageId] = text
        edits += messageId to text
    }
}
