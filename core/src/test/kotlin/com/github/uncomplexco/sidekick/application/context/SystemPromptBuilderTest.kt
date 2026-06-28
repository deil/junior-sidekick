package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatChannelMetadata
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationIntelligenceLevel
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class TurnPromptBuilderTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `renders attached file virtual path`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = listOf("F1")),
                context(conversationId, file("F1", "/data/session/note.txt")),
            )

        assertTrue(prompt.contains("local_path: /data/session/note.txt"), prompt)
    }

    @Test
    fun `renders attached file metadata`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = listOf("F1")),
                context(conversationId, file("F1", "/data/session/note.txt")),
            )

        assertTrue(prompt.contains("filename: note.txt"), prompt)
        assertTrue(prompt.contains("mime_type: text/plain"), prompt)
    }

    @Test
    fun `renders slack conversation identity when koog history is missing`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = emptyList()),
                context(conversationId),
            )

        assertTrue(prompt.contains("channel_id: C123"), prompt)
        assertTrue(prompt.contains("thread_ts: 1700000000.000"), prompt)
    }

    @Test
    fun `does not render slack conversation identity when koog history exists`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = emptyList()),
                context(conversationId, hasKoogMessages = true),
            )

        assertTrue(!prompt.contains("channel_id: C123"), prompt)
        assertTrue(!prompt.contains("thread_ts: 1700000000.000"), prompt)
    }

    @Test
    fun `renders channel metadata when koog history is missing`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = emptyList()),
                context(
                    conversationId,
                    channelMetadata =
                        ChatChannelMetadata(
                            name = "engineering",
                            topic = "Ship <fast>",
                            description = "Build & operate Sidekick",
                        ),
                ),
            )

        assertTrue(prompt.contains("channel_name: engineering"), prompt)
        assertTrue(prompt.contains("topic: Ship &lt;fast&gt;"), prompt)
        assertTrue(prompt.contains("description: Build &amp; operate Sidekick"), prompt)
    }

    @Test
    fun `does not render channel metadata when koog history exists`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = emptyList()),
                context(
                    conversationId,
                    hasKoogMessages = true,
                    channelMetadata = ChatChannelMetadata(name = "engineering", topic = "topic", description = "description"),
                ),
            )

        assertTrue(!prompt.contains("channel_name: engineering"), prompt)
        assertTrue(!prompt.contains("topic: topic"), prompt)
        assertTrue(!prompt.contains("description: description"), prompt)
    }

    @Test
    fun `renders skipped messages after last assistant when koog history exists`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = emptyList(), text = "current reply-worthy message"),
                context(
                    conversationId,
                    hasKoogMessages = true,
                    historyMessages =
                        listOf(
                            message(id = "m1", text = "old skipped", replied = false),
                            message(id = "a1", role = SessionMessageRole.ASSISTANT, text = "assistant reply", replied = true),
                            message(id = "m2", text = "recent skipped", replied = false),
                        ),
                ),
            )

        assertTrue(!prompt.contains("old skipped"), prompt)
        assertTrue(prompt.contains("recent skipped"), prompt)
        assertTrue(prompt.contains("current reply-worthy message"), prompt)
    }

    @Test
    fun `does not duplicate skipped messages when koog history is missing`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = emptyList(), text = "current reply-worthy message"),
                context(
                    conversationId,
                    hasKoogMessages = false,
                    historyMessages = listOf(message(id = "m1", text = "recent skipped", replied = false)),
                ),
            )

        assertTrue(prompt.indexOf("recent skipped") == prompt.lastIndexOf("recent skipped"), prompt)
    }

    private fun builder(): TurnPromptBuilder =
        TurnPromptBuilder(
            AgentConfig(
                name = "Sidekick",
                stateDir = dir.resolve("state").toString(),
                workingDir = dir.resolve("workspace").toString(),
            ),
            skills = { com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog(emptyList()) },
        )

    private fun context(
        conversationId: ConversationId,
        file: SessionFileRef? = null,
        hasKoogMessages: Boolean = false,
        historyMessages: List<SessionMessage> = emptyList(),
        channelMetadata: ChatChannelMetadata? = null,
    ): TurnContext =
        TurnContext(
            conversationId = conversationId,
            virtualPaths = virtualPaths(),
            turnId = "turn",
            currentMessageIds = listOf("m1"),
            currentFiles = emptyList(),
            sessionFiles = listOfNotNull(file),
            intelligenceLevel = ConversationIntelligenceLevel.NORMAL,
            history =
                ConversationHistory(
                    compactions = emptyList(),
                    messages = historyMessages,
                    hasKoogMessages = hasKoogMessages,
                ),
            channelMetadata = channelMetadata,
            mcpServers = emptyList(),
        )

    private fun virtualPaths(): VirtualPaths =
        VirtualPaths(
            sessionRoot = dir.resolve("state/session/attachments"),
            skillsRoot = dir.resolve("workspace/skills"),
            globalRoot = dir.resolve("workspace/global"),
            workRoot = dir.resolve("state/bash/work"),
            projectRoot = dir.resolve("workspace"),
        )

    private fun message(
        fileIds: List<String> = emptyList(),
        id: String = "m1",
        role: SessionMessageRole = SessionMessageRole.USER,
        text: String = "read this",
        replied: Boolean? = null,
    ): SessionMessage =
        SessionMessage(
            id = id,
            role = role,
            author = MessageAuthor(username = "alice", fullName = "Alice"),
            text = text,
            fileIds = fileIds,
            createdAtMs = 1,
            replied = replied,
        )

    private fun file(
        id: String,
        localPath: String,
    ): SessionFileRef =
        SessionFileRef(
            id = id,
            name = "note.txt",
            mimetype = "text/plain",
            filetype = "text",
            urlPrivateDownload = "https://files.slack.com/files-pri/T-F/download/file",
            localPath = localPath,
        )
}
