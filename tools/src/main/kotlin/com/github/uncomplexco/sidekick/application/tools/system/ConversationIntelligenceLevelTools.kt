package com.github.uncomplexco.sidekick.application.tools.system

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.conversation.ConversationIntelligenceLevel
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import com.slack.api.methods.MethodsClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

class ConversationIntelligenceLevelTools(
    private val slackClient: MethodsClient,
    private val ctx: TurnContext,
    private val store: ConversationStateStore,
) : ToolSet {
    private val log = LoggerFactory.getLogger(ConversationIntelligenceLevelTools::class.java)

    @Tool
    @LLMDescription(
        "Controls assistant's intelligence level for current conversation. Persists across turns. Use only the the user explicitly requests to enable 'ultrathink' mode. Disable immediately when user requests so.",
    )
    fun enableTokenmaxxin(
        @LLMDescription("Enables or disables ultrathink mode for this conversation")
        enabled: Boolean = true,
    ): TokenmaxxinResult {
        val intelligenceLevel = if (enabled) ConversationIntelligenceLevel.ULTRATHINK else ConversationIntelligenceLevel.NORMAL

        if (intelligenceLevel == ConversationIntelligenceLevel.NORMAL) {
            slackClient.reactionsAdd { req ->
                req.channel(ctx.conversationId.channelId)
                req.timestamp(ctx.currentMessageId)
                req.name("teddy_bear")
            }
        } else {
            slackClient.reactionsAdd { req ->
                req.channel(ctx.conversationId.channelId)
                req.timestamp(ctx.currentMessageId)
                req.name("student")
            }
        }

        runBlocking {
            store.withSessionLock(ctx.conversationId) {
                val state = store.load(ctx.conversationId)
                val previousIntelligenceLevel = state.intelligenceLevel
                state.intelligenceLevel = intelligenceLevel
                store.save(ctx.conversationId, state)
                log.info(
                    "Conversation intelligence level changed: conversationId={} {} -> {}",
                    ctx.conversationId.lockKey(),
                    previousIntelligenceLevel,
                    intelligenceLevel,
                )
            }
        }

        return TokenmaxxinResult(
            intelligenceLevel = intelligenceLevel.name.lowercase(),
            conversationId = ctx.conversationId.lockKey(),
        )
    }
}

@Serializable
data class TokenmaxxinResult(
    val intelligenceLevel: String,
    val conversationId: String,
)
