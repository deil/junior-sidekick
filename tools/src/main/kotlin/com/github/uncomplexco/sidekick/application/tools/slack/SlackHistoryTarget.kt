package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
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

internal class SlackHistoryTargetResolver(private val projects: ProjectsRepository) {
    fun resolveThread(
        destinationChannelId: String,
        url: String?,
        channelId: String?,
        slackTs: String?,
    ): SlackThreadTarget {
        if (url != null) {
            validate(url.isNotBlank()) { "Slack message `url` must not be empty." }
            return parseArchiveUrl(url)
        }
        validate(!channelId.isNullOrBlank() && !slackTs.isNullOrBlank()) { MISSING_THREAD_TARGET }
        val timestamp = validateSlackTimestamp(requireNotNull(slackTs))
        return SlackThreadTarget(
            resolveChannel(destinationChannelId, requireNotNull(channelId)),
            timestamp,
        )
    }

    fun resolveChannel(destinationChannelId: String, value: String?): String {
        if (value == null) return destinationChannelId
        val input = value.trim().removePrefix("slack:")
        validate(input.isNotBlank() && !input.contains(':')) {
            "Slack channel must be a non-empty id, mention, reference, or name."
        }
        val channelId =
            if (SLACK_CHANNEL_ID_RE.matches(input)) input
            else SLACK_CHANNEL_MENTION_RE.matchEntire(input)?.groupValues?.get(1)
        return channelId ?: resolveChannelName(destinationChannelId, input)
    }

    private fun resolveChannelName(destinationChannelId: String, input: String): String {
        val name = input.removePrefix("#")
        validate(name.isNotBlank() && !input.startsWith("<")) {
            "Invalid Slack channel id, mention, or name."
        }
        val workspaceId =
            projects.findByUid(destinationChannelId)?.chatWorkspaceId
                ?: fail("Current Slack workspace is unknown. Use a channel id or mention.")
        val matches = projects.findByName(ChatPlatform.SLACK, workspaceId, name)
        if (matches.isEmpty()) fail("Known Slack channel not found: $name.")
        validate(matches.size == 1) {
            "More than one known Slack channel matches '$name'. Use a channel id."
        }
        return matches.single().externalRefId
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

internal fun validateSlackTimestamp(value: String): String =
    value.trim().also {
        validate(SLACK_MESSAGE_TS_RE.matches(it)) {
            "Slack message timestamp must have the form 1700000000.000000."
        }
    }

internal fun normalizeSlackHistoryBound(value: String?, channelId: String): String? {
    if (value == null) return null
    val input = value.trim()
    val timestamp =
        if (input.startsWith("slack:")) {
            val parts = input.split(':')
            validate(parts.size == 3 && parts[1] == channelId) {
                "Slack timestamp reference must name the target channel $channelId."
            }
            parts[2]
        } else input
    return validateSlackTimestamp(timestamp)
}

internal fun normalizeSlackHistoryCursor(value: String?): String? =
    value?.trim()?.also {
        validate(it.isNotEmpty()) { "Slack history cursor must not be empty." }
    }
