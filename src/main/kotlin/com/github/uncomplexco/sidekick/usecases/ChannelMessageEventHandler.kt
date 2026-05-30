package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.agent.SidekickAgent
import com.github.uncomplexco.sidekick.application.agent.TurnMessage
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ChannelMessageEventHandler(
    private val agent: SidekickAgent,
) {
    fun handle(
        messageId: String,
        sender: String,
        text: String,
        ctx: TurnContext,
    ) {
        if (ctx.chatConversationId.isThread) {
            log.debug("[#${ctx.chatConversationId.channelId}/${ctx.chatConversationId.threadId}] @$sender: $text")
        } else {
            log.debug("[#${ctx.chatConversationId.channelId}] @$sender: $text")
        }

        runBlocking {
            val agentReply = agent.runTurn(TurnMessage(user = sender, text = text))
            ctx.chat.postReply(agentReply)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChannelMessageEventHandler::class.java)
    }
}
