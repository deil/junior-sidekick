package com.github.uncomplexco.sidekick.application.conversation

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import kotlinx.serialization.Serializable

@Serializable
data class ConversationRuntime(
    val stats: ConversationStats = ConversationStats(),
    val activeTurn: ActiveTurn? = null,
)

@Serializable
data class ActiveTurn(
    val id: String,
    val checkpoint: AgentCheckpointData,
)
