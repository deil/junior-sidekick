package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.bolt.App
import com.slack.api.methods.SlackApiException
import java.io.IOException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

@Service
class DeleteSlackMessageUsecase(private val apps: ObjectProvider<App>) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun execute(command: Command): Result {
        logger.info(
            "Slack message deletion requested by={} channel={} message={}",
            command.requestedBy,
            command.channelId,
            command.messageId,
        )
        val result = delete(command.channelId, command.messageId)
        logger.info(
            "Slack message deletion completed by={} channel={} message={} outcome={}",
            command.requestedBy,
            command.channelId,
            command.messageId,
            result,
        )
        return result
    }

    private fun delete(channelId: String, messageId: String): Result {
        val app = apps.ifAvailable ?: return Result.Unavailable

        logger.info("Deleting Slack bot message channel={} message={}", channelId, messageId)
        return try {
            // Slack enforces that a bot token can delete only messages posted by that bot.
            val response = app.client().chatDelete { it.channel(channelId).ts(messageId) }
            if (response.isOk) Result.Deleted
            else {
                logger.warn(
                    "Slack message deletion rejected channel={} message={} error={}",
                    channelId,
                    messageId,
                    response.error,
                )
                when (response.error) {
                    "message_not_found",
                    "channel_not_found" -> Result.NotFound
                    "cant_delete_message",
                    "no_permission",
                    "access_denied" -> Result.NotAllowed
                    "ratelimited" -> Result.RateLimited
                    else -> Result.Unavailable
                }
            }
        } catch (error: SlackApiException) {
            logger.warn(
                "Slack message deletion failed channel={} message={} status={}",
                channelId,
                messageId,
                error.response?.code,
            )
            if (error.response?.code == 429) Result.RateLimited else Result.Unavailable
        } catch (_: IOException) {
            logger.warn(
                "Slack message deletion failed channel={} message={} due to a connection error",
                channelId,
                messageId,
            )
            Result.Unavailable
        }
    }

    data class Command(val channelId: String, val messageId: String, val requestedBy: String)

    sealed interface Result {
        data object Deleted : Result

        data object NotFound : Result

        data object NotAllowed : Result

        data object Unavailable : Result

        data object RateLimited : Result
    }
}
