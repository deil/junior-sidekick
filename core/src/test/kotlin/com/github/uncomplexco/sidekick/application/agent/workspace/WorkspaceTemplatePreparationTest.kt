package com.github.uncomplexco.sidekick.application.agent.workspace

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.testing.virtualPathsFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceTemplatePreparationTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `prepares project without optional templates`() {
        val factory = virtualPathsFactory(config(), ChatPlatform.SLACK)

        val paths = factory.forConversation(ConversationId("C123", "thread"))

        assertTrue(Files.isDirectory(paths.projectRoot))
        assertFalse(Files.exists(paths.projectRoot.resolve("AGENTS.md")))
    }

    @Test
    fun `copies configured project template when preparing a conversation`() {
        val config = config()
        val source =
            Files.createDirectories(
                config.workspaceLayout().templatesDirectoryPath().resolve("project")
            )
        Files.writeString(source.resolve("AGENTS.md"), "project instructions")
        val factory = virtualPathsFactory(config, ChatPlatform.SLACK)

        val paths = factory.forConversation(ConversationId("C123", "thread"))

        assertEquals(
            "project instructions",
            Files.readString(paths.projectRoot.resolve("AGENTS.md")),
        )
    }

    @Test
    fun `another thread receives missing project files without replacing existing ones`() {
        val config = config()
        val source =
            Files.createDirectories(
                config.workspaceLayout().templatesDirectoryPath().resolve("project")
            )
        Files.writeString(source.resolve("AGENTS.md"), "template instructions")
        val factory = virtualPathsFactory(config, ChatPlatform.SLACK)
        val first = factory.forConversation(ConversationId("C123", "first"))
        Files.writeString(first.projectRoot.resolve("AGENTS.md"), "edited instructions")
        Files.writeString(source.resolve("new.txt"), "new content")

        val second = factory.forConversation(ConversationId("C123", "second"))

        assertEquals(first.projectRoot, second.projectRoot)
        assertEquals(
            "edited instructions",
            Files.readString(second.projectRoot.resolve("AGENTS.md")),
        )
        assertEquals("new content", Files.readString(second.projectRoot.resolve("new.txt")))
    }

    @Test
    fun `keeps work template copying in the session work directory`() {
        val config = config()
        val source =
            Files.createDirectories(
                config.workspaceLayout().templatesDirectoryPath().resolve("work")
            )
        Files.writeString(source.resolve("scratch.txt"), "scratch content")
        val factory = virtualPathsFactory(config, ChatPlatform.SLACK)

        val paths = factory.forConversation(ConversationId("C123", "thread"))

        assertEquals("scratch content", Files.readString(paths.workRoot.resolve("scratch.txt")))
        assertFalse(Files.exists(paths.projectRoot.resolve("scratch.txt")))
    }

    private fun config(): AgentConfig =
        AgentConfig(
            "Sidekick",
            dir.resolve("state").toString(),
            dir.resolve("workspace").toString(),
        )
}
