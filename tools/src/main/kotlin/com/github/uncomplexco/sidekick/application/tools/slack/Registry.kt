package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolBase
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.slack.api.methods.MethodsClient

fun slackTools(
    slackClient: MethodsClient,
    ctx: TurnContext,
): List<ToolBase<*, *>> =
    SlackCanvasTools(slackClient, ctx.conversation.conversationId).asTools() + SlackChannelTools(slackClient).asTools() +
        SlackHistoryTools(slackClient, ctx).asTools() + SlackUserTools(slackClient).asTools() +
        SlackReactionTools(slackClient, ctx).asTools() + SlackFileTools(ctx, ctx.conversation.virtualPaths).asTools()
