package com.github.uncomplexco.sidekick.application.chat

import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.triggers.ChatMessageType

data class IncomingChatFile(
    val id: String,
    val name: String,
    val mimetype: String?,
    val filetype: String?,
    val urlPrivateDownload: String,
    val permalink: String,
    val localPath: String?,
)

data class InboundMessage(
    val id: String,
    val createdAtMs: Long,
    val sender: MessageAuthor,
    val text: String,
    val type: ChatMessageType,
    val files: List<IncomingChatFile> = emptyList(),
)
