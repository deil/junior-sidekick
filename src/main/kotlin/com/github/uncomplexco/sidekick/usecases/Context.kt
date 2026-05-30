package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.conversations.ChatConversationId
import com.github.uncomplexco.sidekick.application.conversations.ChatMessage
import com.github.uncomplexco.sidekick.ports.ReplyToMessage

class TurnContext(
    val chatConversationId: ChatConversationId,
    val historyLoader: () -> List<ChatMessage>,
    val chat: ReplyToMessage,
)
