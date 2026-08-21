package com.github.uncomplexco.sidekick.adapters.files

import ai.koog.prompt.message.Message
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ActiveTurn
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationRuntime
import com.github.uncomplexco.sidekick.application.conversation.ConversationSettings
import com.github.uncomplexco.sidekick.application.conversation.ConversationState
import com.github.uncomplexco.sidekick.application.conversation.ConversationStateStore
import com.github.uncomplexco.sidekick.application.conversation.ConversationStats
import com.github.uncomplexco.sidekick.application.conversation.SessionCompaction
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.stats.ConversationUsage
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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
    private val runtimeJson =
        Json(json) {
            prettyPrint = true
        }

    override fun exists(id: ConversationId): Boolean = load(id).messages.isNotEmpty()

    override fun load(id: ConversationId): ConversationState {
        val folder = id.folder(config.stateDirectoryPath())
        val files = loadJsonl<SessionFileRef>(folder.resolve("files.jsonl"))
        val compactions = loadJsonl<SessionCompaction>(folder.resolve("compactions.jsonl"))
        val messages = loadJsonl<SessionMessage>(folder.resolve("messages.jsonl"))
        val koogMessages = loadJsonl<Message>(folder.resolve("koog.jsonl"))
        val runtime = loadRuntime(folder)
        val settings =
            loadJson(
                folder.resolve("settings.json"),
                ConversationSettings.serializer(),
                ConversationSettings(),
            )

        return ConversationState(
            id = id,
            files = files.toMutableList(),
            aiModel = settings.intelligenceLevel,
            subscribed = settings.subscribed,
            compactions = compactions.sortedBy { it.createdAtMs }.toMutableList(),
            messages = messages.sortedBy { it.createdAtMs }.toMutableList(),
            koogMessages = koogMessages.toMutableList(),
            stats = runtime.stats,
        )
    }

    override fun loadUsageStartedBetween(
        startInclusiveMs: Long,
        endExclusiveMs: Long,
    ): List<ConversationUsage> {
        val channelsRoot = config.stateDirectoryPath().resolve("slack/channels")
        if (!Files.isDirectory(channelsRoot)) return emptyList()

        val conversationIds = mutableListOf<ConversationId>()
        Files.newDirectoryStream(channelsRoot).use { channelFolders ->
            channelFolders.filter { Files.isDirectory(it) }.forEach { channelFolder ->
                val channelId = channelFolder.fileName.toString()
                val threadsFolder = channelFolder.resolve("threads")
                if (Files.isDirectory(threadsFolder)) {
                    Files.newDirectoryStream(threadsFolder).use { threadFolders ->
                        threadFolders
                            .filter { Files.isRegularFile(it.resolve("messages.jsonl")) }
                            .filter { it.creationTimeMs() in startInclusiveMs..<endExclusiveMs }
                            .forEach {
                                conversationIds += ConversationId(channelId, it.fileName.toString())
                            }
                    }
                }
            }
        }

        return conversationIds.map { id ->
            val folder = id.folder(config.stateDirectoryPath())
            val messages = loadJsonl<SessionMessage>(folder.resolve("messages.jsonl"))
            val stats = loadRuntime(folder).stats
            ConversationUsage(
                channelId = id.channelId,
                userIds = messages.mapNotNull { it.author?.username }.toSet(),
                consumedInputTokens = stats.consumedInputTokens,
                consumedOutputTokens = stats.consumedOutputTokens,
            )
        }
    }

    private fun Path.creationTimeMs(): Long =
        Files.readAttributes(this, BasicFileAttributes::class.java).creationTime().toMillis()

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
        val runtime = loadRuntime(folder)
        writeRuntime(folder, runtime.copy(stats = state.stats))
    }

    override suspend fun <T> withSessionLock(
        id: ConversationId,
        block: suspend () -> T,
    ): T {
        val lock = locks.computeIfAbsent(id.lockKey()) { Mutex() }
        return lock.withLock { block() }
    }

    override suspend fun saveActiveTurn(
        id: ConversationId,
        activeTurn: ActiveTurn?,
    ) = withSessionLock(id) {
        val folder = id.folder(config.stateDirectoryPath())
        val runtime = loadRuntime(folder)
        writeRuntime(folder, runtime.copy(activeTurn = activeTurn))
    }

    override suspend fun loadActiveTurn(id: ConversationId): ActiveTurn? =
        withSessionLock(id) {
            loadRuntime(id.folder(config.stateDirectoryPath())).activeTurn
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

    private fun loadRuntime(folder: Path): ConversationRuntime {
        val runtimePath = folder.resolve(RUNTIME_FILE)
        if (Files.exists(runtimePath)) {
            return runtimeJson.decodeFromString(Files.readString(runtimePath, StandardCharsets.UTF_8))
        }

        val legacyStats =
            loadJson(
                folder.resolve(LEGACY_STATS_FILE),
                ConversationStats.serializer(),
                ConversationStats(),
            )
        return ConversationRuntime(stats = legacyStats)
    }

    private fun writeRuntime(
        folder: Path,
        runtime: ConversationRuntime,
    ) {
        Files.createDirectories(folder)
        val path = folder.resolve(RUNTIME_FILE)
        val temporary = Files.createTempFile(folder, "runtime-", ".json.tmp")

        try {
            Files.writeString(temporary, runtimeJson.encodeToString(runtime), StandardCharsets.UTF_8)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(folder.resolve(LEGACY_STATS_FILE))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val RUNTIME_FILE = "runtime.json"
        private const val LEGACY_STATS_FILE = "stats.json"
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
