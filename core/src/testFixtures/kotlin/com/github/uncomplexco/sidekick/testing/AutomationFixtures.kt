package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.adapters.files.FilesystemLegacyAutomationsReader
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteAutomationsRepository
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteConfiguration
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteProjectsRepository
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Automation
import com.github.uncomplexco.sidekick.application.core.AutomationImporter
import com.github.uncomplexco.sidekick.application.core.AutomationService
import com.github.uncomplexco.sidekick.application.core.Projects
import com.github.uncomplexco.sidekick.application.core.scheduledTrigger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

class AutomationFixture(val config: AgentConfig, clock: Clock = Clock.systemUTC()) {
    private val configuration = SqliteConfiguration()
    private val source = configuration.sidekickDataSource(config)
    val projects =
        Projects(
            SqliteProjectsRepository(
                configuration.sidekickJdbcClient(source),
                configuration.sidekickTransactions(source),
            ),
            config,
            ChatPlatform.SLACK,
        )

    init {
        projects.register("C123", "C123")
        projects.register("C456", "C456")
    }

    val projectId = requireNotNull(projects.getByUid("C123").id)
    val otherProjectId = requireNotNull(projects.getByUid("C456").id)
    val repository =
        SqliteAutomationsRepository(
            configuration.sidekickJdbcClient(source),
            configuration.sidekickTransactions(source),
        )
    val legacy = FilesystemLegacyAutomationsReader(config)
    val importer = AutomationImporter(repository, legacy, projects, clock)
    val service = AutomationService(repository, legacy, importer, projects, clock)

    fun writeLegacy(projectUid: String, json: String, lastAllocatedUid: Int) {
        val root = config.stateDirectoryPath().resolve("slack/channels/$projectUid")
        Files.createDirectories(root)
        Files.writeString(root.resolve("jobs.jsonl"), json)
        Files.writeString(root.resolve("jobs-sequence"), "$lastAllocatedUid\n")
    }
}

fun automationFixture(root: Path, clock: Clock = Clock.systemUTC()): AutomationFixture =
    AutomationFixture(
        AgentConfig("Test", root.resolve("state").toString(), root.resolve("workspace").toString()),
        clock,
    )

fun testAutomation(uid: Int = 32, instructions: String = "Run the report"): Automation =
    Automation(
        id = uid.toLong(),
        projectId = 1L,
        uid = uid,
        name = "report",
        description = null,
        instructions = instructions,
        trigger = scheduledTrigger("0 9 * * *", "UTC"),
        enabled = true,
        lastRunAt = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
