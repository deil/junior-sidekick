package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.SidekickAgent
import com.github.uncomplexco.sidekick.application.IncomingChatMessage
import com.github.uncomplexco.sidekick.application.TurnMessage
import com.github.uncomplexco.sidekick.application.sessions.AgentSessions
import com.github.uncomplexco.sidekick.application.sessions.ChatConversationId
import com.github.uncomplexco.sidekick.application.sessions.ConversationTriggerPolicy
import com.github.uncomplexco.sidekick.application.sessions.MessageRole
import com.github.uncomplexco.sidekick.application.sessions.SessionMessage
import com.github.uncomplexco.sidekick.application.sessions.TriggerDecision
import com.github.uncomplexco.sidekick.ports.ChatPlatformAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class HandleIncomingChatMessageUsecase(
    private val agentConfig: AgentConfig,
    private val agent: SidekickAgent,
    private val agentSessions: AgentSessions,
    private val triggerPolicy: ConversationTriggerPolicy,
) {
    suspend fun handle(
        conversationId: ChatConversationId,
        message: IncomingChatMessage,
        chat: ChatPlatformAdapter,
    ) {
        log.debug("{} @{}: {}", conversationId.logLabel(), message.sender.username, message.text)

        val decision =
            triggerPolicy.decide(
                messageId = message.id,
                trigger = message.trigger,
                conversationId = conversationId,
            )
        when (decision) {
            TriggerDecision.Ignore -> {
                log.debug("{} ignored trigger={}", conversationId.logLabel(), message.trigger)
                return
            }

            is TriggerDecision.Handle -> {
                handle(message, decision, chat)
            }
        }
    }

    private suspend fun handle(
        message: IncomingChatMessage,
        decision: TriggerDecision.Handle,
        chat: ChatPlatformAdapter,
    ) {
        if (agentConfig.botUsername == null) {
            agentConfig.botUsername = chat.botUsername
        }

        val turn =
            agentSessions.recordIncomingMessage(
                sessionId = decision.sessionId,
                seedHistory = decision.seedHistory,
                historyLoader = chat.historyLoader,
                message =
                    SessionMessage(
                        id = message.id,
                        role = MessageRole.USER,
                        author = message.sender,
                        text = message.text,
                        createdAtMs = message.createdAtMs,
                        explicitMention = decision.explicitMention,
                    ),
            )

        val agentReply = agent.runTurn(turn, TurnMessage(user = message.sender, text = message.text))
        val replyMessageId = chat.reply.postReply(agentReply)

        agentSessions.recordAssistantReply(
            sessionId = decision.sessionId,
            turnId = turn.turnId,
            text = agentReply,
            replyId = replyMessageId.messageId,
            createdAtMs = replyMessageId.timestamp,
            originalMessageId = message.id,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(HandleIncomingChatMessageUsecase::class.java)
    }
}
