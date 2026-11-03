package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingBatch
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob

sealed interface TurnTrigger {
    val chat: ChatPlatformAdapter

    data class Incoming(
        val batch: IncomingBatch,
        override val chat: ChatPlatformAdapter,
    ) : TurnTrigger

    data class Scheduled(
        val conversationId: ConversationId,
        val job: ScheduledJob,
        override val chat: ChatPlatformAdapter,
        val startedAtMs: Long,
    ) : TurnTrigger
}
