package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.slack.api.bolt.context.builtin.EventContext

internal suspend fun loadThreadHistory(
    ctx: EventContext,
    threadTs: String,
    currentTs: String?,
    conversationId: ConversationId,
    fileIngestor: SlackFileIngestor,
): List<ChatMessage> {
    val response =
        ctx.client().conversationsReplies { req ->
            req.channel(ctx.channelId)
            req.ts(threadTs)
        }

    if (!response.isOk) {
        return emptyList()
    }

    return response.messages
        .orEmpty()
        .mapNotNull {
            val text = it.text.trim()
            if (currentTs != null && it.ts == currentTs) return@mapNotNull null

            val files =
                fileIngestor.ingest(
                    conversationId,
                    incomingChatFiles(it.files, it.attachments),
                    summarizeImages = false,
                )
            if (text.isBlank() && files.isEmpty()) return@mapNotNull null

            val botMessage = it.botId != null && it.botId == ctx.botUserId

            return@mapNotNull ChatMessage(
                id = it.ts,
                role = if (botMessage) SessionMessageRole.ASSISTANT else SessionMessageRole.USER,
                author = if (!botMessage) toMessageAuthor(it.user, ctx) else null,
                text = text,
                timestamp = slackTsToMillis(it.ts),
                files = files,
            )
        }
}
