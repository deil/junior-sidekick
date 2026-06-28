package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatChannelMetadata
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.conversation.ConversationIntelligenceLevel
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.turn.koog.ConnectedMcpServer

data class TurnContext(
    val conversationId: ConversationId,
    val virtualPaths: VirtualPaths,
    val turnId: String,
    val currentMessageIds: List<String>,
    val currentFiles: List<IncomingChatFile>,
    val sessionFiles: List<SessionFileRef>,
    val intelligenceLevel: ConversationIntelligenceLevel,
    val history: ConversationHistory,
    val channelMetadata: ChatChannelMetadata? = null,
    val mcpServers: List<ConnectedMcpServer>,
) {
    val currentMessageId = currentMessageIds.last()
}

data class ConversationHistory(
    val compactions: List<SessionCompaction>,
    val messages: List<SessionMessage>,
    val hasKoogMessages: Boolean,
) {
    fun isNotEmpty(): Boolean = messages.isNotEmpty()
}

fun filterOutRecentMessages(
    messages: List<SessionMessage>,
    recentMessages: List<SessionMessage>,
): List<SessionMessage> = messages.filter { hist -> recentMessages.none { hist.id == it.id } }
