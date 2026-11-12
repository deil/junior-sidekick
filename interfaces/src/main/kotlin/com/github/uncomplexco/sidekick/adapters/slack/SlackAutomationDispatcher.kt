package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.adapters.chat.ChatUiRenderer
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.core.AutomationRun
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.ports.AutomationDispatcher
import com.github.uncomplexco.sidekick.usecases.RunAutomationUsecase
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
class SlackAutomationDispatcher(
    private val app: App,
    private val agentConfig: AgentConfig,
    private val runAutomation: RunAutomationUsecase,
    private val scope: SidekickCoroutineScope,
    private val renderer: ChatUiRenderer,
) : AutomationDispatcher {
    override suspend fun dispatch(run: AutomationRun) {
        runAutomation.run(
            run = run,
            chat =
                AutomationSlackChatPlatformAdapter(
                    app.client(),
                    run.project.externalRefId,
                    agentConfig.botUsername!!,
                    scope,
                    renderer,
                ),
        )
    }
}
