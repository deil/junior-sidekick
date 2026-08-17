package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobRun
import com.github.uncomplexco.sidekick.application.turn.TurnExecutor
import org.springframework.stereotype.Component

@Component
class RunScheduledJobUsecase(
    private val agentConfig: AgentConfig,
    private val turnExecutor: TurnExecutor,
) {
    suspend fun run(
        scheduledRun: ScheduledJobRun,
        chat: ChatPlatformAdapter,
    ) {
        if (agentConfig.botUsername == null) {
            agentConfig.botUsername = chat.botUsername
        }

        val job = scheduledRun.job
        val startedAtMs = requireNotNull(job.lastRunAt)
        val conversationId = ConversationId(scheduledRun.channelId, "scheduled_${job.id}_$startedAtMs")
        turnExecutor.runScheduled(conversationId, job, chat, startedAtMs)
    }
}
