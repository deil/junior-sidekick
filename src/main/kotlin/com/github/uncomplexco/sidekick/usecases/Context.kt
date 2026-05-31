package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.sessions.ChatConversationId
import com.github.uncomplexco.sidekick.application.sessions.ChatMessage
import com.github.uncomplexco.sidekick.ports.ReplyToMessage

class ChatConversationContext(
    val chatConversationId: ChatConversationId,
    val historyLoader: () -> List<ChatMessage>,
    val chat: ReplyToMessage,
)
