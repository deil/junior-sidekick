package com.github.uncomplexco.sidekick.application.sessions

import com.github.uncomplexco.sidekick.application.IncomingChatFile
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class AgentSessions(
    private val config: AgentConfig,
    private val contextCompactor: SessionContextCompactor,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun recordIncomingMessage(
        sessionId: SessionId,
        seedHistory: Boolean,
        historyLoader: () -> List<ChatMessage>,
        message: SessionMessage,
    ): TurnContext =
        withSessionLock(sessionId) {
            val turnId = generateTurnId()
            val state = loadOrSeedState(sessionId, seedHistory, historyLoader)
            upsertMessage(state.messages, message)
            contextCompactor.compactIfNeeded(state)
            saveState(sessionId, state)

            TurnContext(
                sessionId = sessionId,
                turnId = turnId,
                currentMessageId = message.id,
                currentFiles = message.files,
                compactions = state.compactions,
                history = state.messages.filter { it.id != message.id },
            )
        }

    fun exists(sessionId: SessionId): Boolean = loadState(sessionId).messages.isNotEmpty()

    suspend fun recordAssistantReply(
        sessionId: SessionId,
        turnId: String,
        text: String,
        replyId: String,
        createdAtMs: Long,
        originalMessageId: String,
    ) = withSessionLock(sessionId) {
        val state = loadState(sessionId)

        state.messages.find { it.id == originalMessageId }?.replied = true

        upsertMessage(
            state.messages,
            SessionMessage(
                id = replyId,
                role = MessageRole.ASSISTANT,
                text = normalizeMessageText(text),
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

        saveState(sessionId, state)
    }

    private fun upsertMessage(
        messages: MutableList<SessionMessage>,
        message: SessionMessage,
    ) {
        val existingIndex = messages.indexOfFirst { it.id == message.id }
        if (existingIndex >= 0) {
            messages[existingIndex] = message
        } else {
            messages += message
            messages.sortBy { it.createdAtMs }
        }
    }

    private fun loadOrSeedState(
        sessionId: SessionId,
        seedHistory: Boolean,
        historyLoader: () -> List<ChatMessage>,
    ): SessionState {
        val state = loadState(sessionId)
        if (state.messages.isEmpty() && seedHistory) {
            val seededHistory = historyLoader()
            seededHistory
                .sortedBy { it.timestamp }
                .map {
                    SessionMessage(
                        id = it.id,
                        role = it.role,
                        author = it.author,
                        text = it.text,
                        createdAtMs = it.timestamp,
                    )
                }.forEach { state.messages.add(it) }
        }

        return state
    }

    private fun loadState(sessionId: SessionId): SessionState {
        val folder = sessionId.folder(config.stateDirectoryPath())
        val compactions = loadJsonl<SessionCompaction>(folder.resolve("compactions.jsonl"))
        val messages = loadJsonl<SessionMessage>(folder.resolve("messages.jsonl"))

        val inflight =
            loadJson(
                folder.resolve("inflight.json"),
                SessionInFlightState.serializer(),
                SessionInFlightState(),
            )

        return SessionState(
            id = sessionId,
            compactions = compactions.sortedBy { it.createdAtMs }.toMutableList(),
            messages = messages.sortedBy { it.createdAtMs }.toMutableList(),
            inflight = inflight,
        )
    }

    private fun saveState(
        key: SessionId,
        state: SessionState,
    ) {
        val folder = key.folder(config.stateDirectoryPath())
        Files.createDirectories(folder)
        writeJsonl(folder.resolve("compactions.jsonl"), state.compactions)
        writeJsonl(folder.resolve("messages.jsonl"), state.messages)
        writeJson(folder.resolve("inflight.json"), SessionInFlightState.serializer(), state.inflight)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun generateTurnId(prefix: String = "turn"): String =
        "${prefix}_${System.currentTimeMillis()}_${Uuid.generateV7().toString().replace("-", "").take(8)}"

    private suspend fun <T> withSessionLock(
        key: SessionId,
        block: suspend () -> T,
    ): T {
        val lock = locks.computeIfAbsent(key.lockKey()) { Mutex() }
        return lock.withLock { block() }
    }

    private inline fun <reified T> loadJsonl(path: Path): List<T> {
        if (!Files.exists(path)) {
            return emptyList()
        }

        return Files
            .readAllLines(path, StandardCharsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { json.decodeFromString<T>(it) }
            .toList()
    }

    private inline fun <reified T> writeJsonl(
        path: Path,
        entries: List<T>,
    ) {
        if (entries.isEmpty()) {
            Files.deleteIfExists(path)
            return
        }

        val content = entries.joinToString("\n") { json.encodeToString(it) } + "\n"
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    private fun <T> loadJson(
        path: Path,
        serializer: KSerializer<T>,
        defaultValue: T,
    ): T {
        if (!Files.exists(path)) {
            return defaultValue
        }

        return json.decodeFromString(serializer, Files.readString(path, StandardCharsets.UTF_8))
    }

    private fun <T> writeJson(
        path: Path,
        serializer: KSerializer<T>,
        value: T,
    ) {
        Files.writeString(path, json.encodeToString(serializer, value), StandardCharsets.UTF_8)
    }

    private fun normalizeMessageText(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(CONTEXT_MAX_MESSAGE_CHARS)

    companion object {
        private const val CONTEXT_MAX_MESSAGE_CHARS = 3200
    }
}
