package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.agents.sessions.AgentsSessionsSetStatusRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.chat.ChatUpdateRequest
import com.slack.api.methods.response.agents.sessions.AgentsSessionsSetStatusResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.methods.response.reactions.ReactionsAddResponse
import com.slack.api.methods.response.reactions.ReactionsRemoveResponse
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred

/** Slack API edge double. Processing expires after an hour; replies do not clear it. */
internal class SlackActivityApi(private val now: () -> Long) {
    private val statuses = mutableMapOf<Pair<String, String>, Activity>()
    private val statusCalls = mutableListOf<StatusCall>()
    private val postedMessages = mutableListOf<ChatPostMessageRequest>()
    private val updatedMessages = mutableListOf<ChatUpdateRequest>()
    val edits: List<ChatUpdateRequest>
        @Synchronized get() = updatedMessages.toList()

    val replies: List<ChatPostMessageRequest>
        @Synchronized get() = postedMessages.toList()

    val calls: List<StatusCall>
        @Synchronized get() = statusCalls.toList()

    @Volatile var beforeStatus: (AgentsSessionsSetStatusRequest) -> Boolean = { true }
    @Volatile var failReplies = false
    var rejectPosts = false
    var rejectEdits = false
    var postError: java.io.IOException? = null
    var editError: java.io.IOException? = null
    val replied = CompletableDeferred<Unit>()
    val cleared = CompletableDeferred<Unit>()

    @Synchronized
    fun visibleStatus(channel: String, thread: String): String? =
        statuses[channel to thread]?.takeIf { now() < it.expiresAt }?.text

    @Suppress("UNCHECKED_CAST")
    val client =
        Proxy.newProxyInstance(
            MethodsClient::class.java.classLoader,
            arrayOf(MethodsClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "agentsSessionsSetStatus" -> {
                    val configure =
                        args!![0]
                            as
                            RequestConfigurator<
                                AgentsSessionsSetStatusRequest.AgentsSessionsSetStatusRequestBuilder
                            >
                    val request =
                        configure.configure(AgentsSessionsSetStatusRequest.builder()).build()
                    recordStatus(request)
                    val accepted = beforeStatus(request)
                    if (accepted) setStatus(request)
                    AgentsSessionsSetStatusResponse().also {
                        it.isOk = accepted
                        if (!accepted) it.error = "temporary_failure"
                    }
                }
                "reactionsAdd" -> ReactionsAddResponse().also { it.isOk = true }
                "reactionsRemove" -> ReactionsRemoveResponse().also { it.isOk = true }
                "chatPostMessage" -> {
                    postError?.let { throw it }
                    check(!failReplies) { "Reply failed" }
                    val configure =
                        args!![0]
                            as
                            RequestConfigurator<
                                ChatPostMessageRequest.ChatPostMessageRequestBuilder
                            >
                    val request = configure.configure(ChatPostMessageRequest.builder()).build()
                    recordReply(request)
                    replied.complete(Unit)
                    ChatPostMessageResponse().also {
                        it.isOk = !rejectPosts
                        if (rejectPosts) it.error = "temporary_failure"
                        it.ts = "${1700000000L + replies.size}.000"
                    }
                }
                "chatUpdate" -> {
                    editError?.let { throw it }
                    val configure =
                        args!![0] as RequestConfigurator<ChatUpdateRequest.ChatUpdateRequestBuilder>
                    val request = configure.configure(ChatUpdateRequest.builder()).build()
                    recordEdit(request)
                    ChatUpdateResponse().also {
                        it.isOk = !rejectEdits
                        if (rejectEdits) it.error = "temporary_failure"
                        it.ts = request.ts
                    }
                }
                else -> error("Unexpected Slack API method: ${method.name}")
            }
        } as MethodsClient

    @Synchronized
    private fun recordEdit(request: ChatUpdateRequest) {
        updatedMessages += request
    }

    @Synchronized
    private fun recordReply(request: ChatPostMessageRequest) {
        postedMessages += request
    }

    @Synchronized
    private fun recordStatus(request: AgentsSessionsSetStatusRequest) {
        statusCalls += StatusCall(now(), request)
    }

    @Synchronized
    private fun setStatus(request: AgentsSessionsSetStatusRequest) {
        val key = request.channelId to request.threadTs
        if (request.status == "active") {
            statuses.remove(key)
            cleared.complete(Unit)
        } else {
            statuses[key] = Activity(request.status, now() + 3_600_000)
        }
    }

    private data class Activity(val text: String, val expiresAt: Long)

    data class StatusCall(val time: Long, val request: AgentsSessionsSetStatusRequest)
}
