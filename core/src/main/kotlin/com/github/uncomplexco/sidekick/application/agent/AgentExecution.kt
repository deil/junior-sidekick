package com.github.uncomplexco.sidekick.application.agent

import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachment
import com.github.uncomplexco.sidekick.application.turn.RecordedTurn
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import java.nio.file.Files
import kotlin.collections.forEach

fun interface AgentTurnExecutor {
    suspend fun execute(
        turn: RecordedTurn,
        chat: ChatPlatformAdapter,
    ): TurnExecutionResult
}

data class AgentResponse(
    val text: String,
    val attachments: List<ReplyAttachment> = emptyList(),
) {
    fun deleteAttachments() {
        attachments.forEach { Files.deleteIfExists(it.path) }
    }
}

data class TurnExecutionResult(
    val response: AgentResponse,
    val stats: AgentStats,
)
