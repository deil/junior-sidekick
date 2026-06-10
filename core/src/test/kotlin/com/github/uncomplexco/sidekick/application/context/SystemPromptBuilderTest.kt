package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
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
                context(conversationId, file("F1", "session:/files/note.txt")),
            )

        assertTrue(prompt.contains("local_path: session:/files/note.txt"), prompt)
    }

    @Test
    fun `renders attached file metadata`() {
        val conversationId = ConversationId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = listOf("F1")),
                context(conversationId, file("F1", "session:/files/note.txt")),
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
    ): TurnContext =
        TurnContext(
            conversationId = conversationId,
            turnId = "turn",
            currentMessageIds = listOf("m1"),
            currentFiles = emptyList(),
            sessionFiles = listOfNotNull(file),
            history =
                ConversationHistory(
                    compactions = emptyList(),
                    messages = historyMessages,
                    hasKoogMessages = hasKoogMessages,
                ),
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
