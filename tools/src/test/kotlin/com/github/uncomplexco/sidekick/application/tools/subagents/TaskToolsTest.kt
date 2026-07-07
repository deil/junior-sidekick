package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.TurnActivityIndicator
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals

class TaskToolsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `runs subagent with prompt and reports task status`() {
        val runner = RecordingSubagentRunner("x".repeat(50_010))
        val chat = RecordingChatPlatform()
        val tools = TaskTools(runner, turnContext(tempDir), chat)

        val result = tools.task("Find owners", "Inspect the repo", "default")

        assertEquals(listOf("Inspect the repo"), runner.prompts)
        assertEquals("x".repeat(50_010), result)
        assertEquals(listOf("default task - Find owners", null), chat.continuedWith)
    }

    @Test
    fun `rejects invalid task arguments before updating status`() {
        val runner = RecordingSubagentRunner("ok")
        val chat = RecordingChatPlatform()
        val tools = TaskTools(runner, turnContext(tempDir), chat)

        assertThrows<ToolException.ValidationFailure> { tools.task(" ", "Inspect", "default") }
        assertThrows<ToolException.ValidationFailure> { tools.task("x".repeat(81), "Inspect", "default") }
        assertThrows<ToolException.ValidationFailure> { tools.task("Find", " ", "default") }
        assertThrows<ToolException.ValidationFailure> { tools.task("Find", "x".repeat(10_001), "default") }
        assertThrows<ToolException.ValidationFailure> { tools.task("Find", "Inspect", " ") }

        assertEquals(emptyList(), runner.prompts)
        assertEquals(emptyList(), chat.continuedWith)
    }

    @Test
    fun `converts runner failure to tool failure and restores thinking`() {
        val runner = FailingSubagentRunner()
        val chat = RecordingChatPlatform()
        val tools = TaskTools(runner, turnContext(tempDir), chat)

        val error = assertThrows<ToolException.ValidationFailure> { tools.task("Find", "Inspect", "default") }

        assertEquals("boom", error.message)
        assertEquals(listOf("default task - Find", null), chat.continuedWith)
    }
}

private class RecordingSubagentRunner(
    private val result: String,
) : SubagentRunner {
    val prompts = mutableListOf<String>()

    override suspend fun run(
        ctx: TurnContext,
        prompt: String,
    ): String {
        prompts += prompt
        return result
    }
}

private class FailingSubagentRunner : SubagentRunner {
    override suspend fun run(
        ctx: TurnContext,
        prompt: String,
    ): String = error("boom")
}

private class RecordingChatPlatform : ChatPlatformAdapter {
    override val botUsername = "USIDEKICK"
    val continuedWith = mutableListOf<String?>()
    override val activity =
        object : TurnActivityIndicator {
            override fun start(text: String?) = Unit

            override fun `continue`(text: String?) {
                continuedWith += text
            }

            override fun toolCall(name: String) = Unit

            override fun clear() = Unit

            override fun endTurn() = Unit
        }

    override fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

    override suspend fun postReply(text: String): ReplyResult = ReplyResult("reply", 1)

    override fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = files
}

private fun turnContext(tempDir: Path): TurnContext =
    TurnContext(
        conversation =
            ConversationContext(
                conversationId = ConversationId("C123", "1.23"),
                virtualPaths = VirtualPaths(tempDir, tempDir, tempDir, tempDir, tempDir),
                history = ConversationHistory(emptyList(), emptyList(), hasKoogMessages = false),
                mcpServers = emptyList(),
            ),
        turnId = "turn-1",
        currentMessageIds = listOf("message-1"),
        currentFiles = emptyList(),
        sessionFiles = emptyList(),
        aiModelProfile = AiModelProfile.NORMAL,
    )
