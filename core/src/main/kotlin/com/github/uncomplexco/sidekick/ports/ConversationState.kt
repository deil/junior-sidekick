package com.github.uncomplexco.sidekick.ports.conversation

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.stats.ConversationUsage

interface ConversationStateStore {
    fun exists(id: ConversationId): Boolean

    fun load(id: ConversationId): ConversationState

    fun loadUsageStartedBetween(
        startInclusiveMs: Long,
        endExclusiveMs: Long,
    ): List<ConversationUsage>

    fun save(
        id: ConversationId,
        state: ConversationState,
    )

    suspend fun <T> withSessionLock(
        id: ConversationId,
        block: suspend () -> T,
    ): T
}
