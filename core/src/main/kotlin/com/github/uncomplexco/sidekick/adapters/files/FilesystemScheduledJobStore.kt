package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJob
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import com.github.uncomplexco.sidekick.application.scheduling.ScheduledJobStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Component
class FilesystemScheduledJobStore(
    private val config: AgentConfig,
) : ScheduledJobStore {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun channelIds(): List<String> {
        val channelsRoot = config.stateDirectoryPath().resolve("slack/channels")
        if (!Files.isDirectory(channelsRoot)) return emptyList()

        return Files.newDirectoryStream(channelsRoot).use { channels ->
            channels
                .filter { Files.isRegularFile(it.resolve(JOBS_FILE)) }
                .map { it.fileName.toString() }
                .sorted()
        }
    }

    override fun allocateId(channelId: String): Int {
        val path = channelPath(channelId).resolve(SEQUENCE_FILE)
        val lastId = if (Files.isRegularFile(path)) Files.readString(path, StandardCharsets.UTF_8).trim().toInt() else 0
        val nextId = lastId + 1
        writeAtomically(path, "$nextId\n")
        return nextId
    }

    override fun load(channelId: String): List<ScheduledJob> {
        val path = jobsPath(channelId)
        if (!Files.isRegularFile(path)) return emptyList()

        return Files
            .readAllLines(path, StandardCharsets.UTF_8)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { json.decodeFromString<ScheduledJob>(it) }
            .toList()
    }

    override fun save(
        channelId: String,
        jobs: List<ScheduledJob>,
    ) {
        val path = jobsPath(channelId)
        if (jobs.isEmpty()) {
            Files.deleteIfExists(path)
            return
        }

        val content = jobs.joinToString("\n") { json.encodeToString(it) } + "\n"
        writeAtomically(path, content)
    }

    private fun writeAtomically(
        path: Path,
        content: String,
    ) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "jobs-", ".jsonl.tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override suspend fun <T> withChannelLock(
        channelId: String,
        block: suspend () -> T,
    ): T = locks.computeIfAbsent(channelId) { Mutex() }.withLock { block() }

    private fun channelPath(channelId: String): Path =
        config
            .stateDirectoryPath()
            .resolve("slack/channels")
            .resolve(sanitizePathSegment(channelId))

    private fun jobsPath(channelId: String): Path = channelPath(channelId).resolve(JOBS_FILE)

    private companion object {
        const val JOBS_FILE = "jobs.jsonl"
        const val SEQUENCE_FILE = "jobs-sequence"
    }
}
