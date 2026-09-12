package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob

sealed interface TurnTrigger {
    data class Incoming(
        val batch: IncomingBatch,
        val chat: ChatPlatformAdapter,
    ) : TurnTrigger

    data class Scheduled(
        val conversationId: ConversationId,
        val job: ScheduledJob,
        val chat: ChatPlatformAdapter,
        val startedAtMs: Long,
    ) : TurnTrigger
}
