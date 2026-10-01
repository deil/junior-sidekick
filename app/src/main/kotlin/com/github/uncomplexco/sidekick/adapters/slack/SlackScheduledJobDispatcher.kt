package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobDispatcher
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobRun
import com.github.uncomplexco.sidekick.usecases.RunScheduledJobUsecase
import com.slack.api.bolt.App
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "adapters.chat",
    name = ["platform"],
    havingValue = "slack",
    matchIfMissing = true,
)
@ConditionalOnExpression(
    $$"'${adapters.slack.bot.token:}' != '' and '${adapters.slack.bot.signing-secret:}' != ''"
)
class SlackScheduledJobDispatcher(
    private val app: App,
    private val agentConfig: AgentConfig,
    private val runScheduledJob: RunScheduledJobUsecase,
) : ScheduledJobDispatcher {
    override suspend fun dispatch(run: ScheduledJobRun) {
        runScheduledJob.run(
            scheduledRun = run,
            chat =
                ScheduledSlackChatPlatformAdapter(
                    app.client(),
                    run.channelId,
                    agentConfig.botUsername!!,
                ),
        )
    }
}
