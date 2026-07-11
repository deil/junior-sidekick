package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.ToolException
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.ReplyResult
import com.github.uncomplexco.sidekick.application.chat.TurnActivityIndicator
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class TaskToolTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `description lists available agent types`() {
        val tool = tool(RecordingSubagentRunner("ok"))

        assertContains(tool.descriptor.description, "Available agent types and the tools they have access to:")
        assertContains(tool.descriptor.description, "  - general: General-purpose subagent")
    }

    @Test
    fun `runs subagent with prompt type and status`() {
        val runner = RecordingSubagentRunner("x".repeat(50_010))
        val chat = RecordingChatPlatform()
        val tool = tool(runner, chat)

        val result = runBlocking { tool.execute(TaskTool.Args(description = "Find owners", prompt = "Inspect the repo")) }

        assertEquals(listOf("Inspect the repo"), runner.prompts)
        assertEquals(listOf("general"), runner.subagentTypes)
        assertEquals("x".repeat(50_010), result)
        assertEquals(listOf("general task - Find owners", null), chat.continuedWith)
    }

    @Test
    fun `rejects invalid task arguments before updating status`() {
        val runner = RecordingSubagentRunner("ok")
        val chat = RecordingChatPlatform()
        val tool = tool(runner, chat)

        assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args(" ", "Inspect", "general")) } }
        assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args("x".repeat(81), "Inspect", "general")) } }
        assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args("Find", " ", "general")) } }
        assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args("Find", "x".repeat(10_001), "general")) } }
        assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args("Find", "Inspect", " ")) } }
        assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args("Find", "Inspect", "missing")) } }

        assertEquals(emptyList(), runner.prompts)
        assertEquals(emptyList(), chat.continuedWith)
    }

    @Test
    fun `converts runner failure to tool failure and restores thinking`() {
        val chat = RecordingChatPlatform()
        val tool = tool(FailingSubagentRunner(), chat)

        val error = assertThrows<ToolException.ValidationFailure> { runBlocking { tool.execute(TaskTool.Args("Find", "Inspect", "general")) } }

        assertEquals("boom", error.message)
        assertEquals(listOf("general task - Find", null), chat.continuedWith)
    }

    private fun tool(
        runner: SubagentRunner,
        chat: RecordingChatPlatform = RecordingChatPlatform(),
    ): TaskTool =
        TaskTool(
            runner = runner,
            ctx = turnContext(tempDir),
            chat = chat,
            availableSubagents = listOf(Subagent("general", "General-purpose subagent", "Prompt")),
        )
}

private class RecordingSubagentRunner(
    private val result: String,
) : SubagentRunner {
    val prompts = mutableListOf<String>()
    val subagentTypes = mutableListOf<String>()

    override suspend fun run(
        ctx: TurnContext,
        subagentType: String,
        prompt: String,
    ): String {
        subagentTypes += subagentType
        prompts += prompt
        return result
    }
}

private class FailingSubagentRunner : SubagentRunner {
    override suspend fun run(
        ctx: TurnContext,
        subagentType: String,
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

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> = emptyList()

    override suspend fun postReply(reply: ChatReply): ReplyResult = ReplyResult("reply", 1)

    override suspend fun ingestFiles(
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
