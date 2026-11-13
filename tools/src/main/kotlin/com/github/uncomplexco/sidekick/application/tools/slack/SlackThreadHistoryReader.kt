package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.core.ProjectType
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Conversation
import com.slack.api.model.Message
import org.slf4j.LoggerFactory

private const val THREAD_HISTORY_LIMIT = 100
private const val THREAD_HISTORY_MAX_LIMIT = 1000
private const val THREAD_HISTORY_PAGES = 10
private const val THREAD_HISTORY_MAX_PAGES = 10
private const val THREAD_HISTORY_PAGE_SIZE = 200
private val log = LoggerFactory.getLogger(SlackThreadHistoryReader::class.java)

internal class SlackThreadHistoryReader(
    private val client: MethodsClient,
    private val projects: ProjectsRepository,
) {
    fun read(
        destinationChannelId: String,
        url: String?,
        channelId: String?,
        slackTs: String?,
        limit: Int?,
        scanDepth: Int?,
    ): SlackThreadHistoryResult {
        val target = SlackThreadTargetResolver(client).resolve(url, channelId, slackTs)
        val requestedLimit = normalizeThreadHistoryLimit(limit)
        val maxPages = normalizeThreadHistoryScanDepth(scanDepth)
        val channel = checkAccess(destinationChannelId, target.channelId)
        val replies = readReplies(target, channel, requestedLimit, maxPages)

        if (replies.messages.isEmpty()) fail("No messages found for this thread.")
        val first = replies.messages.first()
        return SlackThreadHistoryResult(
            channelId = target.channelId,
            channelName =
                channel.name ?: projects.findByUid(target.channelId)?.name ?: target.channelId,
            joinedChannel = replies.joined,
            threadTs =
                target.threadTs
                    ?: first.threadTs?.takeIf { it.isNotBlank() }
                    ?: first.ts?.takeIf { it.isNotBlank() }
                    ?: target.lookupTs,
            count = replies.messages.size,
            messages = replies.messages.map { it.toSlackHistoryMessage() },
            nextCursor = replies.cursor,
        )
    }

    private fun readReplies(
        target: SlackThreadTarget,
        channel: Conversation,
        requestedLimit: Int,
        maxPages: Int,
    ): ThreadReplies {
        val messages = mutableListOf<Message>()
        var cursor: String? = null
        var joined = false

        for (page in 1..maxPages) {
            val pageSize = minOf(THREAD_HISTORY_PAGE_SIZE, requestedLimit - messages.size)
            fun fetch() =
                withSlackRetries(
                    "Read Slack thread channel=${target.channelId} ts=${target.lookupTs} page=$page"
                ) {
                    client.conversationsReplies { request ->
                        request
                            .channel(target.channelId)
                            .ts(target.lookupTs)
                            .limit(pageSize)
                            .cursor(cursor)
                    }
                }
            var replies = fetch()
            val missingMembership = !replies.isOk && replies.error == "not_in_channel"
            if (missingMembership && channel.canJoin() && !joined) {
                joinChannel(target)
                joined = true
                replies = fetch()
            }
            if (!replies.isOk) fail(threadHistoryError(replies.error))
            messages += replies.messages.orEmpty().take(pageSize)
            cursor = replies.responseMetadata?.nextCursor?.takeIf { it.isNotBlank() }
            if (messages.size >= requestedLimit || cursor == null) break
        }

        return ThreadReplies(messages, cursor, joined)
    }

    private fun joinChannel(target: SlackThreadTarget) {
        val membership =
            withSlackRetries("Join Slack channel ${target.channelId}") {
                client.conversationsJoin { it.channel(target.channelId) }
            }
        if (!membership.isOk)
            fail("Failed to join Slack channel: ${membership.error ?: "unknown_error"}.")
        log.info("Joined Slack channel {} to read thread {}", target.channelId, target.lookupTs)
    }

    private fun checkAccess(destinationChannelId: String, channelId: String): Conversation {
        val currentChannel = destinationChannelId == channelId
        val stored = projects.findByUid(channelId)
        if (!currentChannel && stored?.type == ProjectType.PRIVATE) {
            fail("Cannot read a private Slack channel outside the current conversation.")
        }
        val info =
            withSlackRetries("Read Slack channel metadata $channelId") {
                client.conversationsInfo { it.channel(channelId) }
            }
        if (currentChannel)
            return info.channel
                ?: Conversation().apply {
                    id = channelId
                    name = stored?.name ?: channelId
                }
        if (!info.isOk || info.channel == null)
            fail("Slack channel is inaccessible: ${info.error ?: "channel_not_found"}.")
        val channel = info.channel
        if (!channel.isPublicChannel())
            fail("Only public Slack channels can be read outside the current conversation.")
        return channel
    }
}

private data class ThreadReplies(
    val messages: List<Message>,
    val cursor: String?,
    val joined: Boolean,
)

private fun Conversation.canJoin(): Boolean = !isMember && isPublicChannel()

private fun Conversation.isPublicChannel(): Boolean = isChannel && !isPrivate && !isIm && !isMpim

internal fun normalizeThreadHistoryLimit(value: Int?): Int {
    val limit = value ?: THREAD_HISTORY_LIMIT
    validate(limit >= 1) { "Slack history limit must be greater than or equal to 1." }
    return minOf(limit, THREAD_HISTORY_MAX_LIMIT)
}

internal fun normalizeThreadHistoryScanDepth(value: Int?): Int {
    val pages = value ?: THREAD_HISTORY_PAGES
    validate(pages >= 1) { "Slack history scan_depth must be greater than or equal to 1." }
    return minOf(pages, THREAD_HISTORY_MAX_PAGES)
}

private fun threadHistoryError(error: String?): String =
    when (error) {
        "not_in_channel" ->
            "Sidekick is not a member of this Slack channel and could not read its history."
        "missing_scope" ->
            "Missing Slack history scopes. Grant the history scope for this conversation type and reinstall the Slack app."
        else -> "Failed to read Slack thread history: ${error ?: "unknown_error"}."
    }
