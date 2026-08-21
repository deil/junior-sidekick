package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobRun
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobDispatcher
import com.github.uncomplexco.sidekick.usecases.RunScheduledJobUsecase
import com.slack.api.bolt.App
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression(
    $$"'${adapters.slack.bot.token:}' != '' and '${adapters.slack.bot.signing-secret:}' != ''",
)
class SlackScheduledJobDispatcher(
    private val app: App,
    private val agentConfig: AgentConfig,
    private val runScheduledJob: RunScheduledJobUsecase,
) : ScheduledJobDispatcher {
    override suspend fun dispatch(run: ScheduledJobRun) {
        val botUserId = botUserId()
        runScheduledJob.run(
            scheduledRun = run,
            chat = ScheduledSlackChatPlatformAdapter(app.client(), run.channelId, botUserId),
        )
    }

    private fun botUserId(): String {
        agentConfig.botUsername?.takeIf { it.isNotBlank() }?.let { return it }

        val response = app.client().authTest { it }
        check(response.isOk && !response.userId.isNullOrBlank()) {
            "Slack auth.test failed: ${response.error ?: "missing user_id"}"
        }
        return response.userId.also { agentConfig.botUsername = it }
    }
}
