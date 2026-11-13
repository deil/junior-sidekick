package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.fail
import com.github.uncomplexco.sidekick.application.core.ProjectType
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Conversation
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SlackHistoryAccess::class.java)

internal data class SlackHistoryChannel(val id: String, val name: String?, val canJoin: Boolean)

internal class SlackHistoryAccess(
    private val client: MethodsClient,
    private val projects: ProjectsRepository,
) {
    fun resolve(destinationChannelId: String, channelId: String): SlackHistoryChannel {
        val current = channelId == destinationChannelId
        val stored = projects.findByUid(channelId)
        if (!current && stored?.type == ProjectType.PRIVATE)
            fail("Cannot read a private Slack channel outside the current conversation.")
        val info =
            withSlackRetries("Read Slack channel metadata $channelId") {
                client.conversationsInfo { it.channel(channelId) }
            }
        val channel = info.channel
        if (!current) {
            if (!info.isOk || channel == null)
                fail("Slack channel is inaccessible: ${info.error ?: "channel_not_found"}.")
            if (!channel.isPublicChannel())
                fail("Only public Slack channels can be read outside the current conversation.")
        }
        val storedName =
            stored?.name?.takeIf {
                it.isNotBlank() && it != stored.uid && it != stored.externalRefId
            }
        return SlackHistoryChannel(
            channelId,
            channel?.name?.takeIf { it.isNotBlank() } ?: storedName,
            channel != null && !channel.isMember && channel.isPublicChannel(),
        )
    }

    fun join(channelId: String) {
        val response =
            withSlackRetries("Join Slack channel $channelId") {
                client.conversationsJoin { it.channel(channelId) }
            }
        if (!response.isOk)
            fail("Failed to join Slack channel: ${response.error ?: "unknown_error"}.")
        log.info("Joined Slack channel {} to read history", channelId)
    }
}

private fun Conversation.isPublicChannel(): Boolean = isChannel && !isPrivate && !isIm && !isMpim
