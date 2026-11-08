package com.github.uncomplexco.example

import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteConfiguration
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteProjectsRepository
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Projects
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.devtools.autoconfigure.DevToolsDataSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class ProjectConfigurationDevToolsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `project configuration with devtools datasource support seeds existing folders`() {
        val workspace = directory.resolve("workspace")
        Files.createDirectories(directory.resolve("state/slack/channels/C123"))

        val project =
            CompletableFuture.supplyAsync {
                    SpringApplicationBuilder(
                            AgentConfig::class.java,
                            SqliteConfiguration::class.java,
                            SqliteProjectsRepository::class.java,
                            Projects::class.java,
                            PlatformConfiguration::class.java,
                        )
                        .web(WebApplicationType.NONE)
                        .run(
                            "--agent.name=Sidekick",
                            "--agent.state-directory=${directory.resolve("state")}",
                            "--agent.working-directory=$workspace",
                            "--spring.devtools.restart.enabled=false",
                        )
                        .use { context ->
                            context.getBean(ProjectsRepository::class.java).findByUid("C123")
                        }
                }
                .get()

        assertNotNull(project)
        assertEquals("C123", project.uid)
        assertEquals("C123", project.name)
        assertEquals("C123", project.externalRefId)
        assertEquals(ChatPlatform.SLACK, project.adapter)
    }

    @Configuration
    @ImportAutoConfiguration(DevToolsDataSourceAutoConfiguration::class)
    class PlatformConfiguration {
        @Bean fun platform(): ChatPlatform = ChatPlatform.SLACK
    }
}
