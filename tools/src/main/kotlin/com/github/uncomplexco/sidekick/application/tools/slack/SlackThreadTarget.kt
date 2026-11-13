package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.validate
import com.slack.api.methods.MethodsClient
import com.slack.api.model.ConversationType
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val SLACK_CHANNEL_ID_RE = Regex("[CGD][A-Z0-9]+")
private val SLACK_CHANNEL_MENTION_RE = Regex("<#([CGD][A-Z0-9]+)(?:\\|[^>]+)?>")
private val SLACK_MESSAGE_TS_RE = Regex("[0-9]+\\.[0-9]{6}")
private val SLACK_ARCHIVE_PATH_RE = Regex("/archives/([CGD][A-Z0-9]+)/p([0-9]{7,})/?")
private const val MISSING_THREAD_TARGET =
    "Provide either a Slack message `url` or both `channel_id` and `slack_ts`."

internal data class SlackThreadTarget(
    val channelId: String,
    val messageTs: String,
    val threadTs: String? = null,
) {
    val lookupTs: String
        get() = threadTs ?: messageTs
}

internal class SlackThreadTargetResolver(private val client: MethodsClient) {
    fun resolve(url: String?, channelId: String?, slackTs: String?): SlackThreadTarget {
        if (url != null) {
            validate(url.isNotBlank()) { "Slack message `url` must not be empty." }
            return parseArchiveUrl(url)
        }
        validate(!channelId.isNullOrBlank() && !slackTs.isNullOrBlank()) { MISSING_THREAD_TARGET }
        val timestamp = validateSlackTimestamp(requireNotNull(slackTs))
        return SlackThreadTarget(resolveChannel(requireNotNull(channelId)), timestamp)
    }

    private fun resolveChannel(value: String): String {
        val input = value.trim()
        val channelId =
            if (SLACK_CHANNEL_ID_RE.matches(input)) input
            else SLACK_CHANNEL_MENTION_RE.matchEntire(input)?.groupValues?.get(1)
        return channelId ?: resolveChannelName(input)
    }

    private fun resolveChannelName(input: String): String {
        val name = input.removePrefix("#")
        validate(name.isNotBlank() && !input.startsWith("<")) {
            "Invalid Slack channel id, mention, or name."
        }
        var cursor: String? = null
        do {
            val page =
                withSlackRetries("Resolve Slack channel name") {
                    client.conversationsList { request ->
                        request
                            .types(
                                listOf(
                                    ConversationType.PUBLIC_CHANNEL,
                                    ConversationType.PRIVATE_CHANNEL,
                                )
                            )
                            .limit(200)
                            .cursor(cursor)
                    }
                }
            if (!page.isOk)
                fail("Failed to resolve Slack channel: ${page.error ?: "unknown_error"}.")
            page.channels
                .orEmpty()
                .find { it.name.equals(name, ignoreCase = true) }
                ?.let {
                    return it.id
                }
            cursor = page.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
        } while (cursor != null)
        fail("Slack channel not found: $name.")
    }
}

private fun parseArchiveUrl(value: String): SlackThreadTarget {
    val trimmed = value.trim()
    val raw =
        if (trimmed.startsWith("<") && trimmed.endsWith(">"))
            trimmed.substring(1, trimmed.length - 1).substringBefore('|')
        else trimmed
    val uri =
        try {
            URI(raw)
        } catch (_: URISyntaxException) {
            throw ToolException.ValidationFailure("Invalid Slack message archive URL.")
        }
    val host = uri.host?.lowercase().orEmpty()
    validate(
        uri.scheme.equals("https", ignoreCase = true) &&
            (host == "slack.com" ||
                host.endsWith(".slack.com") ||
                host == "slack-gov.com" ||
                host.endsWith(".slack-gov.com"))
    ) {
        "Slack message URL must use HTTPS and a Slack or Slack Gov hostname."
    }
    val path = SLACK_ARCHIVE_PATH_RE.matchEntire(uri.path.orEmpty())
    validate(path != null) { "Slack message URL must contain /archives/<channel>/p<timestamp>." }
    val parts = requireNotNull(path).groupValues
    val timestamp = parts[2].let { "${it.dropLast(6)}.${it.takeLast(6)}" }
    val threadTs =
        uri.rawQuery
            ?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "thread_ts" }
            ?.let {
                val decoded =
                    try {
                        URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
                    } catch (_: IllegalArgumentException) {
                        throw ToolException.ValidationFailure(
                            "Invalid thread_ts in Slack message URL."
                        )
                    }
                validateSlackTimestamp(decoded)
            }
    return SlackThreadTarget(parts[1], timestamp, threadTs)
}

private fun validateSlackTimestamp(value: String): String =
    value.trim().also {
        validate(SLACK_MESSAGE_TS_RE.matches(it)) {
            "Slack message timestamp must have the form 1700000000.000000."
        }
    }
