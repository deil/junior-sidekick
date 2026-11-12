package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.core.CreateAutomation
import com.github.uncomplexco.sidekick.application.core.LegacyAutomation
import com.github.uncomplexco.sidekick.application.core.LegacyAutomationProject
import com.github.uncomplexco.sidekick.application.core.scheduledTrigger
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import com.github.uncomplexco.sidekick.ports.LegacyAutomationsReader
import java.nio.file.Files
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component

@Component
class FilesystemLegacyAutomationsReader(private val config: AgentConfig) : LegacyAutomationsReader {
    private val json = Json { ignoreUnknownKeys = true }

    override fun projectUids(): List<String> {
        val root = config.stateDirectoryPath().resolve("slack/channels")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.newDirectoryStream(root).use { projects ->
            projects
                .filter {
                    Files.isRegularFile(it.resolve("jobs.jsonl")) ||
                        Files.isRegularFile(it.resolve("jobs-sequence"))
                }
                .map { it.fileName.toString() }
                .sorted()
        }
    }

    override fun load(projectUid: String): LegacyAutomationProject {
        val directory =
            config
                .stateDirectoryPath()
                .resolve("slack/channels")
                .resolve(sanitizePathSegment(projectUid))
        val path = directory.resolve("jobs.jsonl")
        val records =
            if (Files.isRegularFile(path))
                Files.readAllLines(path)
                    .filter { it.isNotBlank() }
                    .map { json.decodeFromString<LegacyScheduledJob>(it) }
            else emptyList()
        val sequence = directory.resolve("jobs-sequence")
        val lastAllocatedUid =
            if (Files.isRegularFile(sequence)) Files.readString(sequence).trim().toInt() else 0
        return LegacyAutomationProject(
            records.map {
                LegacyAutomation(
                    it.id,
                    CreateAutomation(
                        it.name,
                        it.description,
                        it.prompt,
                        scheduledTrigger(it.schedule, it.timezone),
                        it.enabled,
                    ),
                    it.lastRunAt?.let(Instant::ofEpochMilli),
                )
            },
            maxOf(lastAllocatedUid, records.maxOfOrNull { it.id } ?: 0),
        )
    }
}

@Serializable
private data class LegacyScheduledJob(
    val id: Int,
    val name: String,
    val description: String? = null,
    val schedule: String,
    val timezone: String,
    val prompt: String,
    val enabled: Boolean = true,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
)
