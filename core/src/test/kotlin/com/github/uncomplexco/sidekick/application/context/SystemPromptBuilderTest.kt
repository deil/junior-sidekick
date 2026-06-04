package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.core.MessageAuthor
import com.github.uncomplexco.sidekick.application.core.MessageRole
import com.github.uncomplexco.sidekick.application.session.SessionFileRef
import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionMessage
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
        val sessionId = SessionId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = listOf("F1")),
                context(sessionId, file("F1", "session:/files/note.txt")),
            )

        assertTrue(prompt.contains("local_path: session:/files/note.txt"), prompt)
    }

    @Test
    fun `renders attached file metadata`() {
        val sessionId = SessionId("C123", "1700000000.000")

        val prompt =
            builder().buildSessionTurnPrompt(
                message(fileIds = listOf("F1")),
                context(sessionId, file("F1", "session:/files/note.txt")),
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
        sessionId: SessionId,
        file: SessionFileRef,
    ): TurnContext =
        TurnContext(
            sessionId = sessionId,
            turnId = "turn",
            currentMessageId = "m1",
            currentFiles = emptyList(),
            sessionFiles = listOf(file),
            compactions = emptyList(),
            history = emptyList(),
        )

    private fun message(fileIds: List<String>): SessionMessage =
        SessionMessage(
            id = "m1",
            role = MessageRole.USER,
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
