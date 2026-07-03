package com.github.uncomplexco.sidekick.adapters.files

import ai.koog.prompt.message.Message
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationSettings
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.ConversationStats
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import com.github.uncomplexco.sidekick.ports.conversation.ConversationStateStore
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
class FilesystemConversationStateStore(
    private val config: AgentConfig,
) : ConversationStateStore {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override fun exists(id: ConversationId): Boolean = load(id).messages.isNotEmpty()

    override fun load(id: ConversationId): ConversationState {
        val folder = id.folder(config.stateDirectoryPath())
        val files = loadJsonl<SessionFileRef>(folder.resolve("files.jsonl"))
        val compactions = loadJsonl<SessionCompaction>(folder.resolve("compactions.jsonl"))
        val messages = loadJsonl<SessionMessage>(folder.resolve("messages.jsonl"))
        val koogMessages = loadJsonl<Message>(folder.resolve("koog.jsonl"))
        val settings =
            loadJson(
                folder.resolve("settings.json"),
                ConversationSettings.serializer(),
                ConversationSettings(),
            )

        val stats =
            loadJson(
                folder.resolve("stats.json"),
                ConversationStats.serializer(),
                ConversationStats(),
            )

        return ConversationState(
            id = id,
            files = files.toMutableList(),
            aiModel = settings.intelligenceLevel,
            subscribed = settings.subscribed,
            compactions = compactions.sortedBy { it.createdAtMs }.toMutableList(),
            messages = messages.sortedBy { it.createdAtMs }.toMutableList(),
            koogMessages = koogMessages.toMutableList(),
            stats = stats,
        )
    }

    override fun save(
        id: ConversationId,
        state: ConversationState,
    ) {
        val folder = id.folder(config.stateDirectoryPath())
        Files.createDirectories(folder)
        writeJsonl(folder.resolve("files.jsonl"), state.files)
        writeJsonl(folder.resolve("compactions.jsonl"), state.compactions)
        writeJsonl(folder.resolve("messages.jsonl"), state.messages)
        writeJsonl(folder.resolve("koog.jsonl"), state.koogMessages)
        writeJson(
            folder.resolve("settings.json"),
            ConversationSettings.serializer(),
            ConversationSettings(
                intelligenceLevel = state.aiModel,
                subscribed = state.subscribed,
            ),
        )
        writeJson(folder.resolve("stats.json"), ConversationStats.serializer(), state.stats)
    }

    override suspend fun <T> withSessionLock(
        id: ConversationId,
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

fun ConversationId.folder(stateRoot: Path): Path {
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
