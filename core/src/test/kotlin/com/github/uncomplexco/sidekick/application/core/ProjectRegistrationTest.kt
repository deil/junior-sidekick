package com.github.uncomplexco.sidekick.application.core


import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.testing.sqliteProjectsRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ProjectRegistrationTest {
    @TempDir lateinit var dir: Path

    private fun config() =
        AgentConfig(
            "Sidekick",
            dir.resolve("state").toString(),
            dir.resolve("workspace").toString(),
        )

    @ParameterizedTest(
        name = "{0} conversation in channel {1}, thread {2} registers its project folder"
    )
    @CsvSource(
        "SLACK, C123, 1700000000.000, PRIVATE",
        "SLACK, G123, 1700000000.000, PRIVATE",
        "SLACK, D123, '', DM",
        "DISCORD, 123456789012345678, '', PRIVATE",
    )
    fun `new conversation registers its project folder`(
        platform: ChatPlatform,
        channelId: String,
        threadId: String,
        expectedType: ProjectType,
    ) {
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val registry = Projects(projects, config, platform)
        registry.afterPropertiesSet()
        val factory = VirtualPathsFactory(config, platform, registry)

        val paths = factory.forConversation(ConversationId(channelId, threadId))

        val stored = sqliteProjectsRepository(config).findByUid(channelId)
        assertEquals(
            config.workspaceLayout().projectWorkspacesDirectoryPath().resolve(channelId),
            paths.projectRoot,
        )
        assertTrue(Files.isDirectory(paths.projectRoot))
        assertNotNull(stored)
        assertEquals(channelId, stored.uid)
        assertEquals(channelId, stored.name)
        assertEquals(channelId, stored.externalRefId)
        assertEquals(platform, stored.adapter)
        assertEquals(expectedType, stored.type)
    }

    @Test
    fun `another thread in the same channel keeps the existing project`() {
        val config = config()
        val projects = sqliteProjectsRepository(config)
        val registry = Projects(projects, config, ChatPlatform.SLACK)
        val factory = VirtualPathsFactory(config, ChatPlatform.SLACK, registry)
        factory.forConversation(ConversationId("C123", "1700000000.000"))
        val original = projects.findByUid("C123")

        val paths = factory.forConversation(ConversationId("C123", "1700000001.000"))

        assertEquals(
            config.workspaceLayout().projectWorkspacesDirectoryPath().resolve("C123"),
            paths.projectRoot,
        )
        assertNotNull(original)
        assertEquals(original, sqliteProjectsRepository(config).findByUid("C123"))
    }
}
