package com.github.uncomplexco.sidekick.application.session

import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.ports.ChatMessage
import com.github.uncomplexco.sidekick.ports.SessionStateStore
import org.springframework.stereotype.Component
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class SessionManager(
    private val store: SessionStateStore,
    private val contextCompactor: SessionContextCompactor,
) {
    fun exists(sessionId: SessionId): Boolean = store.exists(sessionId)

    suspend fun recordIncomingMessage(
        sessionId: SessionId,
        seedHistory: Boolean,
        historyLoader: (SessionId) -> List<ChatMessage>,
        message: SessionMessage,
        files: List<IncomingChatFile>,
    ): TurnContext =
        store.withSessionLock(sessionId) {
            val turnId = generateTurnId()
            val state = loadOrSeedState(sessionId, seedHistory, historyLoader)
            upsertFiles(state.files, files.map { it.toSessionFileRef() })
            upsertMessage(state.messages, message)
            contextCompactor.compactIfNeeded(state)
            store.save(sessionId, state)

            TurnContext(
                sessionId = sessionId,
                turnId = turnId,
                currentMessageId = message.id,
                currentFiles = files,
                sessionFiles = state.files,
                compactions = state.compactions,
                history = state.messages.filter { it.id != message.id },
            )
        }

    suspend fun markMessageSkipped(
        sessionId: SessionId,
        messageId: String,
        reason: String,
    ) = store.withSessionLock(sessionId) {
        val state = store.load(sessionId)
        state.messages.find { it.id == messageId }?.let {
            it.replied = false
            it.skippedReason = reason
        }
        store.save(sessionId, state)
    }

    suspend fun recordAssistantReply(
        sessionId: SessionId,
        turnId: String,
        text: String,
        replyId: String,
        createdAtMs: Long,
        originalMessageId: String,
    ) = store.withSessionLock(sessionId) {
        val state = store.load(sessionId)

        state.messages.find { it.id == originalMessageId }?.replied = true

        upsertMessage(
            state.messages,
            SessionMessage(
                id = replyId,
                role = MessageRole.ASSISTANT,
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

        store.save(sessionId, state)
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
        sessionId: SessionId,
        seedHistory: Boolean,
        historyLoader: (SessionId) -> List<ChatMessage>,
    ): SessionState {
        val state = store.load(sessionId)
        if (state.messages.isEmpty() && seedHistory) {
            val seededHistory = historyLoader(sessionId)
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
