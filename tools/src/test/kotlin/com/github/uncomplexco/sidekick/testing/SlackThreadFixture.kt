package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Project
import com.github.uncomplexco.sidekick.application.core.ProjectType
import com.github.uncomplexco.sidekick.application.tools.slack.SlackHistoryTools
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest
import com.slack.api.methods.request.conversations.ConversationsInfoRequest
import com.slack.api.methods.request.conversations.ConversationsJoinRequest
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse
import com.slack.api.methods.response.conversations.ConversationsInfoResponse
import com.slack.api.methods.response.conversations.ConversationsJoinResponse
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse
import com.slack.api.model.Conversation
import com.slack.api.model.Message
import com.slack.api.model.ResponseMetadata
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant

class SlackThreadFixture(root: Path) {
    val calls = mutableListOf<String>()
    val replyRequests = mutableListOf<ConversationsRepliesRequest>()
    val historyRequests = mutableListOf<ConversationsHistoryRequest>()
    val joinRequests = mutableListOf<ConversationsJoinRequest>()
    val storedProjects = mutableMapOf<String, Project>()
    val replies = ArrayDeque<Any>()
    val histories = ArrayDeque<Any>()
    var info =
        ConversationsInfoResponse().apply {
            isOk = true
            channel =
                Conversation().apply {
                    id = "C123"
                    name = "general"
                    isChannel = true
                    isMember = true
                }
        }
    var join = ConversationsJoinResponse().apply { isOk = true }
    private val projects =
        object : ProjectsRepository by NoOpProjectsRepository() {
            override fun findByUid(uid: String): Project? = storedProjects[uid]

            override fun findByName(
                adapter: ChatPlatform,
                workspaceId: String,
                name: String,
            ): List<Project> =
                storedProjects.values.filter {
                    it.adapter == adapter &&
                        it.chatWorkspaceId == workspaceId &&
                        it.name.equals(name, ignoreCase = true)
                }
        }
    private val client =
        Proxy.newProxyInstance(
            MethodsClient::class.java.classLoader,
            arrayOf(MethodsClient::class.java),
        ) { _, method, args ->
            calls += method.name
            when (method.name) {
                "conversationsInfo" -> {
                    configured(args!!, ConversationsInfoRequest.builder()).build()
                    info
                }
                "conversationsHistory" -> {
                    historyRequests +=
                        configured(args!!, ConversationsHistoryRequest.builder()).build()
                    val response = histories.removeFirstOrNull() ?: historyPage()
                    if (response is Throwable) throw response
                    response
                }
                "conversationsReplies" -> {
                    replyRequests +=
                        configured(args!!, ConversationsRepliesRequest.builder()).build()
                    val response = replies.removeFirstOrNull() ?: threadPage()
                    if (response is Throwable) throw response
                    response
                }
                "conversationsJoin" -> {
                    joinRequests += configured(args!!, ConversationsJoinRequest.builder()).build()
                    join
                }
                else -> error("Unexpected Slack call: ${method.name}")
            }
        } as MethodsClient
    val tools = SlackHistoryTools(client, ToolCatalogFixture(root).execution().turn, projects)

    init {
        storeChannel("C123", "general")
    }

    fun storeChannel(
        channelId: String,
        name: String,
        workspaceId: String = "T123",
        type: ProjectType = ProjectType.PUBLIC,
    ) {
        storedProjects[channelId] =
            Project(
                channelId,
                ChatPlatform.SLACK,
                name,
                channelId,
                Instant.EPOCH,
                type = type,
                chatWorkspaceId = workspaceId,
            )
    }

    fun storePrivate(channelId: String) {
        storeChannel(channelId, "private", type = ProjectType.PRIVATE)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <B> configured(args: Array<out Any?>, builder: B): B =
    (args[0] as RequestConfigurator<B>).configure(builder)

fun threadPage(
    count: Int = 1,
    cursor: String? = null,
    threadTs: String? = null,
): ConversationsRepliesResponse =
    ConversationsRepliesResponse().apply {
        isOk = true
        messages =
            (0 until count).map { index ->
                Message().apply {
                    ts = "1700000000.${index.toString().padStart(6, '0')}"
                    this.threadTs = threadTs
                    text = "message $index"
                }
            }
        responseMetadata = ResponseMetadata().apply { nextCursor = cursor }
    }

fun threadFailure(code: String): ConversationsRepliesResponse =
    ConversationsRepliesResponse().apply {
        isOk = false
        error = code
    }

fun historyPage(count: Int = 0, cursor: String? = null): ConversationsHistoryResponse =
    ConversationsHistoryResponse().apply {
        isOk = true
        messages =
            (0 until count).map { index ->
                Message().apply {
                    ts = "${1700000100 - index}.000000"
                    text = "message $index"
                    replyCount = 3
                }
            }
        responseMetadata = ResponseMetadata().apply { nextCursor = cursor }
    }

fun historyFailure(code: String): ConversationsHistoryResponse =
    ConversationsHistoryResponse().apply {
        isOk = false
        error = code
    }
