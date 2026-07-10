package com.github.uncomplexco.sidekick.adapters.http

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.TurnActivityIndicator
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.usecases.HandleIncomingChatMessageUsecase
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = ["adapters.http.enabled"], havingValue = "true")
class SidekickApiController(
    private val handleIncomingChatMessage: HandleIncomingChatMessageUsecase,
    private val conversationStateStore: ConversationStateStore,
    private val scope: CoroutineScope,
    @Value($$"${adapters.http.project-id}") private val projectId: String,
    @Value($$"${adapters.http.user-id}") private val userId: String,
) {
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    fun startSession(
        @RequestBody request: StartSessionRequest,
    ): SendMessageResponse =
        runBlocking {
            val conversationId = newId("api_conversation")
            val chatConversationId = ChatConversationId(channelId = projectId, threadId = conversationId)
            handleMessage(chatConversationId, request.toInboundMessage(), conversationId)
        }

    @PostMapping("/conversations/{conversationId}/messages", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun sendMessage(
        @PathVariable conversationId: String,
        @RequestBody request: SendMessageRequest,
    ): SseEmitter {
        if (conversationId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "conversation_id is required")
        }

        val message = request.toInboundMessage()
        val chatConversationId = ChatConversationId(channelId = projectId, threadId = conversationId)
        val emitter = SseEmitter(0L)

        scope.launch {
            try {
                val response = handleStreamingMessage(chatConversationId, message, conversationId, emitter)
                emitter.sendEvent("final", FinalStreamEvent(response))
                emitter.complete()
            } catch (error: Exception) {
                runCatching { emitter.sendEvent("error", ErrorStreamEvent(error.message ?: "Failed to process message")) }
                emitter.completeWithError(error)
            }
        }

        return emitter
    }

    @GetMapping("/conversations/{conversationId}/messages")
    fun getMessages(
        @PathVariable conversationId: String,
    ): ConversationMessagesResponse {
        if (conversationId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "conversation_id is required")
        }

        val state = conversationStateStore.load(ConversationId(projectId, conversationId))
        return ConversationMessagesResponse(
            conversationId = conversationId,
            messages = state.messages.map { it.toApiMessage() },
        )
    }

    private suspend fun handleMessage(
        chatConversationId: ChatConversationId,
        message: InboundMessage,
        conversationId: String,
    ): SendMessageResponse {
        val chat = HttpChatPlatformAdapter()
        handleIncomingChatMessage.handleNow(chatConversationId, message, chat)
        return SendMessageResponse(
            conversationId = conversationId,
            messageId = message.id,
            replies = chat.replies,
        )
    }

    private suspend fun handleStreamingMessage(
        chatConversationId: ChatConversationId,
        message: InboundMessage,
        conversationId: String,
        emitter: SseEmitter,
    ): SendMessageResponse {
        val chat =
            HttpChatPlatformAdapter(
                StreamingActivityIndicator { status -> emitter.sendEvent("status", StatusStreamEvent(status)) },
            )
        handleIncomingChatMessage.handleNow(chatConversationId, message, chat)
        return SendMessageResponse(
            conversationId = conversationId,
            messageId = message.id,
            replies = chat.replies,
        )
    }

    private fun StartSessionRequest.toInboundMessage(): InboundMessage =
        validateText(text).let { messageText ->
            InboundMessage(
                id = newId("api_msg"),
                createdAtMs = System.currentTimeMillis(),
                sender = MessageAuthor(username = userId, fullName = null),
                text = messageText,
                type = ChatMessageType.ASSISTANT_MESSAGE,
            )
        }

    private fun SendMessageRequest.toInboundMessage(): InboundMessage =
        validateText(text).let { messageText ->
            InboundMessage(
                id = newId("api_msg"),
                createdAtMs = System.currentTimeMillis(),
                sender = MessageAuthor(username = userId, fullName = null),
                text = messageText,
                type = ChatMessageType.ASSISTANT_MESSAGE,
            )
        }

}

private fun SseEmitter.sendEvent(
    name: String,
    data: Any,
) {
    synchronized(this) {
        send(
            SseEmitter
                .event()
                .name(name)
                .data(data),
        )
    }
}

private fun SessionMessage.toApiMessage(): ApiMessage =
    ApiMessage(
        id = id,
        role = role.name.lowercase(),
        status = apiStatus(),
        text = text,
        author = author?.let { ApiAuthor(userId = it.username, userName = it.fullName) },
        createdAtMs = createdAtMs,
        replied = replied,
        skippedReason = skippedReason,
    )

private fun SessionMessage.apiStatus(): String =
    when {
        skippedReason != null -> "skipped"
        replied == true -> "completed"
        replied == null && role == SessionMessageRole.USER -> "processing"
        else -> "pending"
    }

class HttpChatPlatformAdapter(
    override val activity: TurnActivityIndicator = NoopActivityIndicator,
) : ChatPlatformAdapter {
    val replies = mutableListOf<ApiReply>()

    override val botUsername: String = "sidekick-api"

    override fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

    override suspend fun postReply(reply: ChatReply): ReplyResult {
        val apiReply = ApiReply(id = newId("api_reply"), text = reply.text, createdAtMs = System.currentTimeMillis())
        replies += apiReply
        return ReplyResult(apiReply.id, apiReply.createdAtMs)
    }

    override fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = emptyList()
}

private class StreamingActivityIndicator(
    private val emitStatus: (String) -> Unit,
) : TurnActivityIndicator {
    override fun start(text: String?) {
        text?.takeIf { it.isNotBlank() }?.also { runCatching { emitStatus(it) } }
    }

    override fun `continue`(text: String?) {
        text?.takeIf { it.isNotBlank() }?.also { runCatching { emitStatus(it) } }
    }

    override fun toolCall(name: String) = Unit

    override fun clear() = Unit

    override fun endTurn() = Unit
}

data class StartSessionRequest(
    val text: String,
)

data class SendMessageRequest(
    val text: String,
)

data class SendMessageResponse(
    @JsonProperty("conversation_id")
    val conversationId: String,
    @JsonProperty("message_id")
    val messageId: String,
    val replies: List<ApiReply>,
)

data class StatusStreamEvent(
    val message: String,
    @JsonProperty("created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class FinalStreamEvent(
    val response: SendMessageResponse,
)

data class ErrorStreamEvent(
    val message: String,
)

data class ConversationMessagesResponse(
    @JsonProperty("conversation_id")
    val conversationId: String,
    val messages: List<ApiMessage>,
)

data class ApiMessage(
    val id: String,
    val role: String,
    val status: String,
    val text: String,
    val author: ApiAuthor?,
    @JsonProperty("created_at_ms")
    val createdAtMs: Long,
    val replied: Boolean?,
    @JsonProperty("skipped_reason")
    val skippedReason: String?,
)

data class ApiAuthor(
    @JsonProperty("user_id")
    val userId: String,
    @JsonProperty("user_name")
    val userName: String?,
)

data class ApiReply(
    val id: String,
    val text: String,
    @JsonProperty("created_at_ms")
    val createdAtMs: Long,
)

private object NoopActivityIndicator : TurnActivityIndicator {
    override fun start(text: String?) = Unit

    override fun `continue`(text: String?) = Unit

    override fun toolCall(name: String) = Unit


    override fun clear() = Unit

    override fun endTurn() = Unit
}

private fun validateText(text: String): String =
    text.trim().ifBlank {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required")
    }

@OptIn(ExperimentalUuidApi::class)
private fun newId(prefix: String): String = "${prefix}_${Uuid.generateV7().toString().replace("-", "").take(16)}"
