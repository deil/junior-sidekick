package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.conversation.ConversationEffort
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import com.slack.api.methods.MethodsClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@LLMDescription("Conversation effort tools")
class ConversationEffortTools(
    private val slackClient: MethodsClient,
    private val ctx: TurnContext,
    private val store: ConversationStateStore,
) : ToolSet {
    private val log = LoggerFactory.getLogger(ConversationEffortTools::class.java)

    @Tool
    @LLMDescription(
        "Controls assistant's intelligence level for current conversation. Persists across turns. Use only the the user explicitly requests to enable 'ultrathink' mode. Disable immediately when user requests so.",
    )
    fun enableTokenmaxxin(
        @LLMDescription("Enables or disables ultrathink mode for this conversation")
        enabled: Boolean = true,
    ): TokenmaxxinResult {
        val effort = if (enabled) ConversationEffort.ULTRATHINK else ConversationEffort.NORMAL

        slackClient.reactionsAdd { req ->
            req.channel(ctx.conversationId.channelId)
            req.timestamp(ctx.currentMessageId)
            req.name("fire")
        }

        runBlocking {
            store.withSessionLock(ctx.conversationId) {
                val state = store.load(ctx.conversationId)
                val previousEffort = state.effort
                state.effort = effort
                store.save(ctx.conversationId, state)
                log.info(
                    "Conversation effort changed: conversationId={} {} -> {}",
                    ctx.conversationId.lockKey(),
                    previousEffort,
                    effort,
                )
            }
        }

        return TokenmaxxinResult(
            ok = true,
            intelligenceLevel = effort.name.lowercase(),
            conversationId = ctx.conversationId.lockKey(),
        )
    }
}

@Serializable
data class TokenmaxxinResult(
    val ok: Boolean,
    val intelligenceLevel: String,
    val conversationId: String,
)
