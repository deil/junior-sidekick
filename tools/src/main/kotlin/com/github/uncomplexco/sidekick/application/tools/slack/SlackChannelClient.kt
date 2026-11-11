package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.response.conversations.ConversationsListResponse
import com.slack.api.model.ConversationType
import io.ktor.http.HttpStatusCode

internal fun MethodsClient.fetchSlackChannelPage(
    cursor: String?,
    limit: Int,
    includeArchived: Boolean,
): SlackChannelPage {
    try {
        val response: ConversationsListResponse = conversationsList { req ->
            req.types(listOf(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
            req.excludeArchived(!includeArchived)
            req.limit(limit)
            cursor?.let(req::cursor)
            req
        }
        return response.toChannelPage()
    } catch (error: SlackApiException) {
        if (error.response?.code == HttpStatusCode.TooManyRequests.value) {
            throw SlackChannelRateLimited(error.response?.header("Retry-After")?.toLongOrNull())
        }
        throw error
    }
}

private fun ConversationsListResponse.toChannelPage(): SlackChannelPage {
    if (!isOk) {
        if (error == "ratelimited") {
            throw SlackChannelRateLimited(retryAfterSeconds())
        }
        throw ToolException.ValidationFailure(error ?: "Failed to list Slack channels.")
    }
    return SlackChannelPage(
        channels = channels.orEmpty(),
        nextCursor = responseMetadata?.nextCursor?.takeIf { it.isNotBlank() },
    )
}

private fun ConversationsListResponse.retryAfterSeconds(): Long? =
    httpResponseHeaders?.get("retry-after")?.firstOrNull()?.toLongOrNull()
