package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChannelKind
import com.github.uncomplexco.sidekick.application.chat.ChannelVisibility
import com.github.uncomplexco.sidekick.application.chat.ChatChannelDetails
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun loadSlackChannelDetails(
    client: MethodsClient,
    channelId: String,
): ChatChannelDetails? {
    Loggers.SLACK.info("Loading Slack conversation details channelId={}", channelId)
    val response =
        try {
            withContext(Dispatchers.IO) { client.conversationsInfo { it.channel(channelId) } }
        } catch (error: IOException) {
            Loggers.SLACK.warn(
                "Could not load Slack conversation details channelId={}",
                channelId,
                error,
            )
            return null
        } catch (error: SlackApiException) {
            Loggers.SLACK.warn(
                "Could not load Slack conversation details channelId={}",
                channelId,
                error,
            )
            return null
        }

    if (!response.isOk) {
        Loggers.SLACK.warn(
            "Could not load Slack conversation details channelId={}: {}",
            channelId,
            response.error,
        )
        return null
    }

    val channel = response.channel
    return ChatChannelDetails(
        visibility =
            when {
                channel.isIm || channel.isMpim -> ChannelVisibility.DIRECT_MESSAGE
                channel.isPrivate -> ChannelVisibility.PRIVATE
                else -> ChannelVisibility.PUBLIC
            },
        kind =
            when {
                channel.isIm -> ChannelKind.DM
                channel.isMpim -> ChannelKind.GROUP_DM
                else -> ChannelKind.CHANNEL
            },
        isShared = channel.isShared,
        workspaceId = channel.contextTeamId?.takeIf { it.isNotBlank() },
    )
}
