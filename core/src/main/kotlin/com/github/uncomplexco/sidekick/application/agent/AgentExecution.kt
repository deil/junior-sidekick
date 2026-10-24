package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.turn.RecordedTurn
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachment
import java.nio.file.Files
import kotlin.collections.forEach

fun interface AgentTurnExecutor {
    /**
     * [steer] consumes pending input after a complete tool batch, returning the full updated turn
     * or null.
     */
    suspend fun execute(
        turn: RecordedTurn,
        chat: ChatPlatformAdapter,
        steer: AgentSteering,
    ): TurnExecutionResult
}

fun interface AgentSteering {
    suspend fun drain(): RecordedTurn?

    fun stopRequested(): Boolean = false
}

data class AgentResponse(
    val text: String,
    val attachments: List<ReplyAttachment> = emptyList(),
) {
    fun deleteAttachments() {
        attachments.forEach { Files.deleteIfExists(it.path) }
    }
}

sealed interface TurnExecutionResult {
    val stats: AgentStats

    data class Completed(val response: AgentResponse, override val stats: AgentStats) :
        TurnExecutionResult

    data class Stopped(override val stats: AgentStats) : TurnExecutionResult
}
