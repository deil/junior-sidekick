package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.koog.ConnectedMcpServer
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage

data class ConversationContext(
    val conversationId: ConversationId,
    val virtualPaths: VirtualPaths,
    val history: ConversationHistory,
    val mcpServers: List<ConnectedMcpServer>,
)

data class TurnContext(
    val conversation: ConversationContext,
    val turnId: String,
    // Tools retain this context; steering refreshes these fields between tool batches.
    var lastMessageId: String,
    var sessionFiles: List<SessionFileRef>,
    val aiModelProfile: AiModelProfile,
)

data class RecordedTurn(
    val context: TurnContext,
    val messages: List<SessionMessage>,
)

fun resolveRequester(messages: List<SessionMessage>): MessageAuthor? =
    messages.map { it.author }.distinctBy { it?.username }.singleOrNull()

data class ConversationHistory(
    val compactions: List<SessionCompaction>,
    val messages: List<SessionMessage>,
    val hasKoogMessages: Boolean,
) {
    fun isNotEmpty(): Boolean = messages.isNotEmpty()
}
