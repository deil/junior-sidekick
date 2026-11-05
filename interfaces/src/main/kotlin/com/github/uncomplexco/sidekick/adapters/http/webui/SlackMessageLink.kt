package com.github.uncomplexco.sidekick.adapters.http.webui

import java.net.URI
import java.net.URISyntaxException

private val SLACK_MESSAGE_PATH_RE = Regex("^/archives/([CDG][A-Z0-9]+)/p([0-9]+)([0-9]{6})/?$")

internal data class SlackMessageLink(val channelId: String, val timestamp: String) {
    companion object {
        fun parse(value: String): SlackMessageLink? {
            val uri =
                try {
                    URI(value.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            if (
                uri.scheme != "https" ||
                    uri.host?.endsWith(".slack.com") != true ||
                    uri.userInfo != null ||
                    uri.port !in listOf(-1, 443)
            )
                return null
            val match = SLACK_MESSAGE_PATH_RE.matchEntire(uri.rawPath.orEmpty()) ?: return null
            val (channelId, seconds, micros) = match.destructured
            return SlackMessageLink(channelId, "$seconds.$micros")
        }
    }
}
