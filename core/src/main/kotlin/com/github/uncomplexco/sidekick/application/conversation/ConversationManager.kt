package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.TurnHistory
import com.github.uncomplexco.sidekick.application.turn.filterOutRecentMessages
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
import org.springframework.stereotype.Component
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class ConversationManager(
    private val store: ConversationStateStore,
    private val contextCompactor: SessionContextCompactor,
) {
    fun exists(conversationId: ConversationId): Boolean = store.exists(conversationId)

    suspend fun recordIncomingMessages(
        conversationId: ConversationId,
        seedHistory: Boolean,
        historyLoader: (ConversationId) -> List<ChatMessage>,
        messages: List<SessionMessage>,
        files: List<IncomingChatFile>,
    ): TurnContext =
        store.withSessionLock(conversationId) {
            val turnId = generateTurnId()
            val message = messages.last()
            val state = loadOrSeedState(conversationId, seedHistory, historyLoader)
            upsertFiles(state.files, files.map { it.toSessionFileRef() })
            upsertMessage(state.messages, message)
            contextCompactor.compactIfNeeded(state)
            store.save(conversationId, state)

            TurnContext(
                conversationId = conversationId,
                turnId = turnId,
                currentMessageIds = messages.map { it.id },
                currentFiles = files,
                sessionFiles = state.files,
                history =
                    TurnHistory(
                        compactions = state.compactions,
                        messages = filterOutRecentMessages(state.messages, messages),
                    ),
            )
        }

    suspend fun markMessageSkipped(
        conversationId: ConversationId,
        messageId: String,
        reason: String,
    ) = store.withSessionLock(conversationId) {
        val state = store.load(conversationId)
        state.messages.find { it.id == messageId }?.let {
            it.replied = false
            it.skippedReason = reason
        }
        store.save(conversationId, state)
    }

    suspend fun recordAssistantReply(
        conversationId: ConversationId,
        turnId: String,
        text: String,
        replyId: String,
        createdAtMs: Long,
        originalMessageId: String,
    ) = store.withSessionLock(conversationId) {
        val state = store.load(conversationId)

        state.messages.find { it.id == originalMessageId }?.replied = true

        upsertMessage(
            state.messages,
            SessionMessage(
                id = replyId,
                role = SessionMessageRole.ASSISTANT,
                text = normalizeMessageText(text),
                fileIds = emptyList(),
                createdAtMs = createdAtMs,
                replied = true,
            ),
        )

        if (state.inflight.activeTurnId == turnId) {
            state.inflight =
                state.inflight.copy(
                    activeTurnId = null,
                    lastCompletedAtMs = createdAtMs,
                )
        }

        store.save(conversationId, state)
    }

    private fun upsertMessage(
        messages: MutableList<SessionMessage>,
        message: SessionMessage,
    ) {
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx >= 0) {
            messages[idx] = message
        } else {
            messages += message
            messages.sortBy { it.createdAtMs }
        }
    }

    private fun upsertFiles(
        files: MutableList<SessionFileRef>,
        newFiles: List<SessionFileRef>,
    ) {
        newFiles.forEach { file ->
            if (files.any { it.id == file.id }) return@forEach
            files += file
        }
    }

    private fun loadOrSeedState(
        conversationId: ConversationId,
        seedHistory: Boolean,
        historyLoader: (ConversationId) -> List<ChatMessage>,
    ): ConversationState {
        val state = store.load(conversationId)
        if (state.messages.isEmpty() && seedHistory) {
            val seededHistory = historyLoader(conversationId)
            seededHistory
                .sortedBy { it.timestamp }
                .map {
                    upsertFiles(state.files, it.files.map { file -> file.toSessionFileRef() })
                    SessionMessage(
                        id = it.id,
                        role = it.role,
                        author = it.author,
                        text = it.text,
                        fileIds = it.files.map { file -> file.id },
                        createdAtMs = it.timestamp,
                    )
                }.forEach { state.messages.add(it) }
        }

        return state
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun generateTurnId(prefix: String = "turn") =
        "${prefix}_${System.currentTimeMillis()}_${Uuid.generateV7().toString().replace("-", "").take(8)}"

    private fun normalizeMessageText(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(CONTEXT_MAX_MESSAGE_CHARS)

    private fun IncomingChatFile.toSessionFileRef(): SessionFileRef =
        SessionFileRef(
            id = id,
            name = name,
            mimetype = mimetype,
            filetype = filetype,
            displayName = permalink,
            urlPrivateDownload = urlPrivateDownload,
            localPath = localPath!!,
        )

    companion object {
        private const val CONTEXT_MAX_MESSAGE_CHARS = 3200
    }
}
