package com.github.uncomplexco.sidekick.application.turn.checkpoints

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.isTombstone
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import com.github.uncomplexco.sidekick.application.conversation.ActiveTurn
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import org.springframework.stereotype.Component

@Component
class TurnCheckpointPersistence(
    private val store: ConversationStateStore,
) {
    fun forTurn(turnId: String): PersistenceStorageProvider<Unit> = ActiveTurnCheckpointProvider(store, turnId)
}

private class ActiveTurnCheckpointProvider(
    private val store: ConversationStateStore,
    private val turnId: String,
) : PersistenceStorageProvider<Unit> {
    override suspend fun getCheckpoints(
        sessionId: String,
        filter: Unit?,
    ): List<AgentCheckpointData> = getLatestCheckpoint(sessionId, filter)?.let(::listOf).orEmpty()

    override suspend fun saveCheckpoint(
        sessionId: String,
        agentCheckpointData: AgentCheckpointData,
    ) {
        val conversationId = ConversationId.fromLockKey(sessionId)
        val activeTurn = agentCheckpointData.takeUnless { it.isTombstone() }?.let { ActiveTurn(turnId, it) }
        store.saveActiveTurn(conversationId, activeTurn)
    }

    override suspend fun getLatestCheckpoint(
        sessionId: String,
        filter: Unit?,
    ): AgentCheckpointData? =
        store
            .loadActiveTurn(ConversationId.fromLockKey(sessionId))
            ?.takeIf { it.id == turnId }
            ?.checkpoint
}
