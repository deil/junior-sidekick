package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.core.AutomationRun
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.turn.TurnTrigger
import org.springframework.stereotype.Component

@Component
class RunAutomationUsecase(private val turnCoordinator: TurnCoordinator) {
    suspend fun run(
        run: AutomationRun,
        chat: ChatPlatformAdapter,
    ) {
        val automation = run.automation
        val startedAtMs = requireNotNull(automation.lastRunAt).toEpochMilli()
        val conversationId =
            ConversationId(run.project.uid, "automation_${automation.uid}_$startedAtMs")
        turnCoordinator.run(
            TurnTrigger.Automated(
                conversationId = conversationId,
                automation = automation,
                chat = chat,
                startedAtMs = startedAtMs,
            )
        )
    }
}
