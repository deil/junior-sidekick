package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.session.SessionCompaction
import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionInFlightState
import com.github.uncomplexco.sidekick.application.session.SessionMessage
import com.github.uncomplexco.sidekick.application.session.SessionState
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import com.github.uncomplexco.sidekick.ports.SessionStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Component
class FileSessionStateStore(
    private val config: AgentConfig,
) : SessionStateStore {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override fun exists(id: SessionId): Boolean = load(id).messages.isNotEmpty()

    override fun load(id: SessionId): SessionState {
        val folder = id.folder(config.stateDirectoryPath())
        val files = loadJsonl<SessionFileRef>(folder.resolve("files.jsonl"))
        val compactions = loadJsonl<SessionCompaction>(folder.resolve("compactions.jsonl"))
        val messages = loadJsonl<SessionMessage>(folder.resolve("messages.jsonl"))

        val inflight =
            loadJson(
                folder.resolve("inflight.json"),
                SessionInFlightState.serializer(),
                SessionInFlightState(),
            )

        return SessionState(
            id = id,
            files = files.toMutableList(),
            compactions = compactions.sortedBy { it.createdAtMs }.toMutableList(),
            messages = messages.sortedBy { it.createdAtMs }.toMutableList(),
            inflight = inflight,
        )
    }

    override fun save(
        id: SessionId,
        state: SessionState,
    ) {
        val folder = id.folder(config.stateDirectoryPath())
        Files.createDirectories(folder)
        writeJsonl(folder.resolve("files.jsonl"), state.files)
        writeJsonl(folder.resolve("compactions.jsonl"), state.compactions)
        writeJsonl(folder.resolve("messages.jsonl"), state.messages)
        writeJson(folder.resolve("inflight.json"), SessionInFlightState.serializer(), state.inflight)
    }

    override suspend fun <T> withSessionLock(
        id: SessionId,
        block: suspend () -> T,
    ): T {
        val lock = locks.computeIfAbsent(id.lockKey()) { Mutex() }
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
}

fun SessionId.folder(stateRoot: Path): Path {
    val conversationFolder = sanitizePathSegment(channelId)
    return if (threadId.isNullOrBlank()) {
        stateRoot.resolve("slack/channels").resolve(conversationFolder).resolve("session")
    } else {
        stateRoot
            .resolve("slack/channels")
            .resolve(conversationFolder)
            .resolve("threads")
            .resolve(sanitizePathSegment(threadId))
    }
}
