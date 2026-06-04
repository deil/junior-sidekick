package com.github.uncomplexco.sidekick.application.session

import com.github.uncomplexco.sidekick.application.core.MessageAuthor
import com.github.uncomplexco.sidekick.application.session.triggers.ChatTrigger

data class IncomingChatFile(
    val id: String,
    val name: String,
    val mimetype: String?,
    val filetype: String?,
    val urlPrivateDownload: String,
    val permalink: String,
    val localPath: String?,
)

data class IncomingChatMessage(
    val id: String,
    val createdAtMs: Long,
    val sender: MessageAuthor,
    val text: String,
    val trigger: ChatTrigger,
    val files: List<IncomingChatFile> = emptyList(),
)
