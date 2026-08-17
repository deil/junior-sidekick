package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.TurnResultHandler
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.slack.api.methods.MethodsClient

class ScheduledSlackChatPlatformAdapter(
    client: MethodsClient,
    channelId: String,
    override val botUsername: String,
) : SlackBackedChatPlatformAdapter {
    override val resultHandler: TurnResultHandler = SlackTurnResultHandler(client, channelId, threadId = null)

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = emptyList()
}
