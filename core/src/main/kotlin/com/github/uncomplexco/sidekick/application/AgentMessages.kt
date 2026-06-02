package com.github.uncomplexco.sidekick.application

import com.github.uncomplexco.sidekick.application.sessions.MessageAuthor
import com.github.uncomplexco.sidekick.application.sessions.triggers.ChatTrigger

data class TurnMessage(
    val user: MessageAuthor,
    val text: String,
)

data class IncomingChatFile(
    val id: String,
    val name: String?,
    val title: String?,
    val mimetype: String?,
    val filetype: String?,
    val urlPrivateDownload: String,
    val permalink: String?,
)

data class IncomingChatMessage(
    val id: String,
    val createdAtMs: Long,
    val sender: MessageAuthor,
    val text: String,
    val trigger: ChatTrigger,
    val files: List<IncomingChatFile> = emptyList(),
)
