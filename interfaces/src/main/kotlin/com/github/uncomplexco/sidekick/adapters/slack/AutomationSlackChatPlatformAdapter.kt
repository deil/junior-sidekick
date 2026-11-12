package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.chat.ChatUiRenderer
import com.github.uncomplexco.sidekick.application.chat.ChatChannelDetails
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatUserDetails
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.SlackBackedChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.slack.api.methods.MethodsClient

class AutomationSlackChatPlatformAdapter(
    private val client: MethodsClient,
    channelId: String,
    override val botUsername: String,
    scope: SidekickCoroutineScope,
    renderer: ChatUiRenderer = ChatUiRenderer(),
) : SlackBackedChatPlatformAdapter {
    private val output =
        SlackTurnOutput(client, channelId, threadId = null, scope = scope, renderer = renderer)
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        emptyList()

    override suspend fun loadUserDetails(chatId: String): ChatUserDetails? =
        loadSlackUserDetails(client, chatId)

    override suspend fun loadChannelDetails(channelId: String): ChatChannelDetails? =
        loadSlackChannelDetails(client, channelId)

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = emptyList()
}
