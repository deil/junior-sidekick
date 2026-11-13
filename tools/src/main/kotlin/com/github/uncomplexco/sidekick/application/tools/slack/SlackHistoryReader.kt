package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.fail
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.slack.api.methods.MethodsClient
import com.slack.api.model.Message

private const val HISTORY_DEFAULT_LIMIT = 100
private const val HISTORY_MAX_LIMIT = 1000
private const val HISTORY_MAX_PAGES = 10
private const val HISTORY_PAGE_SIZE = 200

internal class SlackHistoryReader(private val client: MethodsClient, projects: ProjectsRepository) {
    private val targets = SlackHistoryTargetResolver(projects)
    private val access = SlackHistoryAccess(client, projects)

    fun readThread(
        destinationChannelId: String,
        url: String?,
        channelId: String?,
        slackTs: String?,
        limit: Int?,
        scanDepth: Int?,
    ): SlackThreadHistoryResult {
        val target = targets.resolveThread(destinationChannelId, url, channelId, slackTs)
        val options = historyOptions(limit, scanDepth)
        val channel = access.resolve(destinationChannelId, target.channelId)
        val result =
            readPages(channel, options) { size, cursor ->
                val response = client.conversationsReplies {
                    it.channel(target.channelId).ts(target.lookupTs).limit(size).cursor(cursor)
                }
                SlackHistoryPage(
                    response.isOk,
                    response.error,
                    response.messages.orEmpty(),
                    response.responseMetadata?.nextCursor,
                )
            }
        if (result.messages.isEmpty()) fail("No messages found for this thread.")
        val first = result.messages.first()
        return SlackThreadHistoryResult(
            channelId = target.channelId,
            channelName = channel.name,
            joinedChannel = result.joined,
            threadTs =
                target.threadTs
                    ?: first.threadTs?.takeIf { it.isNotBlank() }
                    ?: first.ts?.takeIf { it.isNotBlank() }
                    ?: target.lookupTs,
            count = result.messages.size,
            messages = result.messages.map { it.toSlackHistoryMessage() },
            nextCursor = result.cursor,
        )
    }

    fun readChannel(
        destinationChannelId: String,
        channelId: String?,
        options: SlackChannelReadOptions,
    ): SlackChannelReadResult {
        val target = targets.resolveChannel(destinationChannelId, channelId)
        val pageOptions =
            historyOptions(
                options.limit,
                options.scanDepth,
                normalizeSlackHistoryCursor(options.cursor),
            )
        val oldest = normalizeSlackHistoryBound(options.oldest, target)
        val latest = normalizeSlackHistoryBound(options.latest, target)
        val channel = access.resolve(destinationChannelId, target)
        val result =
            readPages(channel, pageOptions) { size, cursor ->
                val response = client.conversationsHistory { request ->
                    request.channel(target).limit(size).cursor(cursor).oldest(oldest).latest(latest)
                    options.boundsInclusive?.let(request::inclusive)
                    request
                }
                SlackHistoryPage(
                    response.isOk,
                    response.error,
                    response.messages.orEmpty(),
                    response.responseMetadata?.nextCursor,
                )
            }
        return SlackChannelReadResult(
            target,
            channel.name,
            result.joined,
            result.messages.size,
            result.messages.map { it.toSlackHistoryMessage() },
            result.cursor,
        )
    }

    private fun readPages(
        channel: SlackHistoryChannel,
        options: HistoryOptions,
        fetch: (Int, String?) -> SlackHistoryPage,
    ): HistoryRead {
        var result = collectPages(channel.id, options, fetch)
        val missingMembership = result.error == "not_in_channel"
        val joined = missingMembership && channel.canJoin
        if (joined) {
            access.join(channel.id)
            result = collectPages(channel.id, options, fetch)
        }
        if (result.error != null) fail(slackReadError(result.error))
        return result.copy(joined = joined)
    }
}

internal data class SlackChannelReadOptions(
    val limit: Int? = null,
    val scanDepth: Int? = null,
    val cursor: String? = null,
    val oldest: String? = null,
    val latest: String? = null,
    val boundsInclusive: Boolean? = null,
)

private data class HistoryOptions(val limit: Int, val maxPages: Int, val cursor: String?)

private data class SlackHistoryPage(
    val ok: Boolean,
    val error: String?,
    val messages: List<Message>,
    val cursor: String?,
)

private data class HistoryRead(
    val messages: List<Message>,
    val cursor: String?,
    val error: String? = null,
    val joined: Boolean = false,
)

private fun historyOptions(limit: Int?, pages: Int?, cursor: String? = null): HistoryOptions {
    val requestedLimit = limit ?: HISTORY_DEFAULT_LIMIT
    val requestedPages = pages ?: HISTORY_MAX_PAGES
    validate(requestedLimit >= 1) { "Slack history limit must be greater than or equal to 1." }
    validate(requestedPages >= 1) { "Slack history scan_depth must be greater than or equal to 1." }
    return HistoryOptions(
        minOf(requestedLimit, HISTORY_MAX_LIMIT),
        minOf(requestedPages, HISTORY_MAX_PAGES),
        cursor,
    )
}

private fun collectPages(
    channelId: String,
    options: HistoryOptions,
    fetch: (Int, String?) -> SlackHistoryPage,
): HistoryRead {
    val messages = mutableListOf<Message>()
    var cursor = options.cursor
    for (page in 1..options.maxPages) {
        val size = minOf(HISTORY_PAGE_SIZE, options.limit - messages.size)
        val response =
            withSlackRetries("Read Slack history channel=$channelId page=$page") {
                fetch(size, cursor)
            }
        if (!response.ok) return HistoryRead(messages, cursor, response.error ?: "unknown_error")
        messages += response.messages.take(size)
        cursor = response.cursor?.takeIf { it.isNotBlank() }
        if (messages.size >= options.limit || cursor == null) break
    }
    return HistoryRead(messages, cursor)
}

private fun slackReadError(error: String): String =
    when (error) {
        "invalid_cursor" ->
            "The supplied Slack history cursor is no longer valid. Retry the lookup without `cursor` to start from the newest page again."
        "not_in_channel" ->
            "Sidekick is not a member of this Slack channel and could not read its history."
        "missing_scope" ->
            "Missing Slack history scopes. Grant the history scope for this conversation type and reinstall the Slack app."
        else -> "Failed to read Slack history: $error."
    }
