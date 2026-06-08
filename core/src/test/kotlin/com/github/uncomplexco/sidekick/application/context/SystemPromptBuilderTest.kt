package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.TurnHistory
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

    private fun builder(): TurnPromptBuilder =
        TurnPromptBuilder(
            AgentConfig(
                name = "Sidekick",
                stateDir = dir.resolve("state").toString(),
                workingDir = dir.resolve("workspace").toString(),
            ),
        )

    private fun context(
        conversationId: ConversationId,
        file: SessionFileRef,
    ): TurnContext =
        TurnContext(
            conversationId = conversationId,
            turnId = "turn",
            currentMessageIds = listOf("m1"),
            currentFiles = emptyList(),
            sessionFiles = listOf(file),
            history = TurnHistory(compactions = emptyList(), messages = emptyList()),
        )

    private fun message(fileIds: List<String>): SessionMessage =
        SessionMessage(
            id = "m1",
            role = SessionMessageRole.USER,
            author = MessageAuthor(username = "alice", fullName = "Alice"),
            text = "read this",
            fileIds = fileIds,
            createdAtMs = 1,
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
