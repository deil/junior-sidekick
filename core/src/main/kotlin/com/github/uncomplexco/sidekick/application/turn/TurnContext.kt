package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.turn.koog.ConnectedMcpServer

data class ConversationContext(
    val conversationId: ConversationId,
    val virtualPaths: VirtualPaths,
    val history: ConversationHistory,
    val mcpServers: List<ConnectedMcpServer>,
)

data class TurnContext(
    val conversation: ConversationContext,
    val turnId: String,
    val currentMessageIds: List<String>,
    val currentFiles: List<IncomingChatFile>,
    val sessionFiles: List<SessionFileRef>,
    val aiModelProfile: AiModelProfile,
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
