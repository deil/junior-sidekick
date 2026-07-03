package com.github.uncomplexco.sidekick.application.tools.slack

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackFileToolsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `supports html markdown and text files`() {
        assertTrue(isSupportedSlackTextFile(file(mimetype = "text/html")))
        assertTrue(isSupportedSlackTextFile(file(mimetype = "text/markdown", filetype = "markdown")))
        assertTrue(isSupportedSlackTextFile(file(mimetype = "text/plain", name = "note.txt")))

        assertFalse(isSupportedSlackTextFile(file(mimetype = "application/pdf", filetype = "pdf", name = "note.pdf")))
    }

    @Test
    fun `read returns virtual session path for attached file`() {
        val conversationId = ConversationId("C123", "1700000000.000")
        val file = file(id = "F123", name = "note.md", mimetype = "text/markdown", localPath = "/data/session/F123-note.md")
        val sessionRoot = conversationId.folder(dir)
        val virtualPaths =
            VirtualPaths(
                sessionRoot = sessionRoot.resolve("attachments"),
                skillsRoot = dir.resolve("workspace/skills"),
                globalRoot = dir.resolve("workspace/global"),
                workRoot = sessionRoot.resolve("work"),
                projectRoot = dir.resolve("workspace"),
            )
        Files.createDirectories(sessionRoot.resolve("attachments"))
        Files.writeString(sessionRoot.resolve("attachments/F123-note.md"), "hello\n")

        val result =
            SlackFileTools(
                ctx =
                    TurnContext(
                        conversation =
                            ConversationContext(
                                conversationId = conversationId,
                                virtualPaths = virtualPaths,
                                history =
                                    ConversationHistory(
                                        compactions = emptyList(),
                                        messages = emptyList(),
                                        hasKoogMessages = false,
                                    ),
                                mcpServers = emptyList(),
                            ),
                        turnId = "turn",
                        currentMessageIds = listOf("m1"),
                        currentFiles = emptyList(),
                        sessionFiles = listOf(file),
                        aiModelProfile = AiModelProfile.NORMAL,
                    ),
                virtualPaths = virtualPaths,
            ).slackFileRead("F123")

        assertContains(result, "<path>/data/session/F123-note.md</path>")
        assertContains(result, "1: hello")
    }

    private fun file(
        id: String = "F123",
        name: String = "file.txt",
        mimetype: String? = null,
        filetype: String? = null,
        localPath: String = "/data/session/$id-$name",
    ): SessionFileRef =
        SessionFileRef(
            id = id,
            name = name,
            displayName = "https://slack.example/files/$id",
            mimetype = mimetype,
            filetype = filetype,
            urlPrivateDownload = "https://files.slack.com/files-pri/T-F/download/file",
            localPath = localPath,
        )
}
