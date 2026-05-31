package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.SidekickAgent
import com.github.uncomplexco.sidekick.application.agent.TurnMessage
import com.github.uncomplexco.sidekick.application.sessions.AgentSessions
import com.github.uncomplexco.sidekick.application.sessions.MessageAuthor
import com.github.uncomplexco.sidekick.application.sessions.MessageRole
import com.github.uncomplexco.sidekick.application.sessions.SessionId
import com.github.uncomplexco.sidekick.application.sessions.SessionMessage
import com.github.uncomplexco.sidekick.application.sessions.toSessionId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AppMentionEventHandler(
    private val agent: SidekickAgent,
    private val agentSessions: AgentSessions,
) {
    suspend fun handle(
        messageId: String,
        messageTimestamp: Long,
        sender: MessageAuthor,
        text: String,
        ctx: ChatConversationContext,
    ) {
        log.debug("[#${ctx.chatConversationId.channelId}] @${sender.username}: $text")

        val sessionId =
            if (ctx.chatConversationId.isThread) {
                ctx.chatConversationId.toSessionId()
            } else {
                SessionId(ctx.chatConversationId.channelId!!, messageId)
            }

        val turn =
            agentSessions.recordIncomingMessage(
                sessionId = sessionId,
                message =
                    SessionMessage(
                        id = messageId,
                        role = MessageRole.USER,
                        author = sender,
                        text = text,
                        createdAtMs = messageTimestamp,
                        explicitMention = true,
                    ),
            )

        val agentReply = agent.runTurn(turn, TurnMessage(user = sender, text = text))
        val replyMessageId = ctx.chat.postReply(agentReply)

        agentSessions.recordAssistantReply(
            sessionId = sessionId,
            turnId = turn.turnId,
            text = agentReply,
            replyId = replyMessageId.messageId,
            createdAtMs = replyMessageId.timestamp,
            originalMessageId = messageId,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppMentionEventHandler::class.java)
    }
}
