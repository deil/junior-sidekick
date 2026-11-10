package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChannelVisibility
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun loadSlackChannelVisibility(
    client: MethodsClient,
    channelId: String,
): ChannelVisibility? {
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
    return when {
        channel.isIm || channel.isMpim -> ChannelVisibility.DIRECT_MESSAGE
        channel.isPrivate -> ChannelVisibility.PRIVATE
        else -> ChannelVisibility.PUBLIC
    }
}
