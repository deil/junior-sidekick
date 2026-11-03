package com.github.uncomplexco.sidekick.application.tools.subagents

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolRegistry
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.testing.CancellingSubagentRunner
import com.github.uncomplexco.sidekick.testing.FailingSubagentRunner
import com.github.uncomplexco.sidekick.testing.RecordingSubagentRunner
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class TaskToolTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `description lists available agent types`() {
        val tool = tool(RecordingSubagentRunner("ok"))

        assertContains(
            tool.descriptor.description,
            "Available agent types and the tools they have access to:",
        )
        assertContains(tool.descriptor.description, "  - general: General-purpose subagent")
    }

    @Test
    fun `runs subagent with prompt type and status`() {
        val runner = RecordingSubagentRunner("x".repeat(50_010))
        val chat = RecordingChatPlatform()
        val tool = tool(runner, chat)

        val result = runBlocking {
            tool.execute(TaskTool.Args(description = "Find owners", prompt = "Inspect the repo"))
        }

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

        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tool.execute(TaskTool.Args(" ", "Inspect", "general")) }
        }
        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tool.execute(TaskTool.Args("x".repeat(81), "Inspect", "general")) }
        }
        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tool.execute(TaskTool.Args("Find", " ", "general")) }
        }
        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tool.execute(TaskTool.Args("Find", "x".repeat(10_001), "general")) }
        }
        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tool.execute(TaskTool.Args("Find", "Inspect", " ")) }
        }
        assertThrows<ToolException.ValidationFailure> {
            runBlocking { tool.execute(TaskTool.Args("Find", "Inspect", "missing")) }
        }

        assertEquals(emptyList(), runner.prompts)
        assertEquals(emptyList(), chat.continuedWith)
    }

    @Test
    fun `converts runner failure to tool failure and restores thinking`() {
        val chat = RecordingChatPlatform()
        val tool = tool(FailingSubagentRunner(), chat)

        val error =
            assertThrows<ToolException.ValidationFailure> {
                runBlocking { tool.execute(TaskTool.Args("Find", "Inspect", "general")) }
            }

        assertEquals("boom", error.message)
        assertEquals(listOf("general task - Find", null), chat.continuedWith)
    }

    @Test
    fun `each task gets a fresh agent and run in the parent conversation`() = runBlocking {
        val runner = RecordingSubagentRunner("ok")
        val task = tool(runner)

        task.execute(TaskTool.Args("Find", "Inspect"))
        task.execute(TaskTool.Args("Find again", "Inspect again"))

        assertNotEquals(runner.agentIds[0], runner.agentIds[1])
        assertNotEquals(runner.runIds[0].runId, runner.runIds[1].runId)
        assertNotEquals(runner.agentIds[0], runner.runIds[0].runId)
        assertEquals(
            List(2) { ConversationId("C123", "1.23") },
            runner.runIds.map { it.conversationId },
        )
    }

    @Test
    fun `task run ID matches the context used to bind its tools`() = runBlocking {
        val runner = RecordingSubagentRunner("ok")
        val boundContexts = mutableListOf<TurnContext>()
        val task =
            tool(
                runner,
                bindTools = {
                    boundContexts += it
                    ToolRegistry.EMPTY
                },
            )

        task.execute(TaskTool.Args("Find", "Inspect"))

        assertEquals(boundContexts.single().turnId, runner.runIds.single().runId)
        assertSame(boundContexts.single().conversation.virtualPaths, runner.virtualPaths.single())
        assertSame(boundContexts.single().conversation, runner.conversations.single())
        assertNotEquals("turn-1", runner.runIds.single().runId)
    }

    @Test
    fun `completed task reports child usage to parent`() = runBlocking {
        val usage = AgentUsageStats(2, 100, 20)
        val reported = mutableListOf<AgentUsageStats>()
        val task = tool(RecordingSubagentRunner("ok", usage), onUsage = reported::add)

        val result = task.execute(TaskTool.Args("Find", "Inspect"))

        assertEquals("ok", result)
        assertEquals(listOf(usage), reported)
    }

    @Test
    fun `failed task reports partial usage before returning tool failure`() {
        val usage = AgentUsageStats(1, 30, 8)
        val reported = mutableListOf<AgentUsageStats>()
        val task = tool(FailingSubagentRunner(usage = usage), onUsage = reported::add)

        val error =
            assertThrows<ToolException.ValidationFailure> {
                runBlocking { task.execute(TaskTool.Args("Find", "Inspect")) }
            }

        assertEquals("boom", error.message)
        assertEquals(listOf(usage), reported)
    }

    @Test
    fun `cancellation propagates and restores thinking without reporting completion`() {
        val cancellation = CancellationException("Cancelled")
        val chat = RecordingChatPlatform()
        val reported = mutableListOf<AgentUsageStats>()
        val task = tool(CancellingSubagentRunner(cancellation), chat, reported::add)

        val error =
            assertThrows<CancellationException> {
                runBlocking { task.execute(TaskTool.Args("Find", "Inspect")) }
            }

        assertSame(cancellation, error)
        assertEquals(listOf("general task - Find", null), chat.continuedWith)
        assertEquals(emptyList(), reported)
    }

    private fun tool(
        runner: SubagentRunner,
        chat: RecordingChatPlatform = RecordingChatPlatform(),
        onUsage: (AgentUsageStats) -> Unit = {},
        bindTools: suspend (TurnContext) -> ToolRegistry = { ToolRegistry.EMPTY },
    ): TaskTool =
        TaskTool(
            runner = runner,
            ctx = turnContext(tempDir),
            activityReporter = chat.lifecycle,
            availableSubagents = listOf(Subagent("general", "General-purpose subagent", "Prompt")),
            onSubagentCompleted = onUsage,
            bindTools = bindTools,
        )
}

private class RecordingChatPlatform : ChatPlatformAdapter {
    override val botUsername = "USIDEKICK"
    val continuedWith = mutableListOf<String?>()
    private val output =
        object : TurnLifecycle, ResponseDelivery {
            override fun begin() = Unit

            override fun progress(text: String?) {
                continuedWith += text
            }

            override fun end() = Unit

            override suspend fun markProcessing(message: IncomingMessage) = Unit

            override suspend fun deliver(
                response: AgentResponse,
                metadata: ResponseMetadata,
            ): PublishedMessage = PublishedMessage("reply", 1)

            override suspend fun markCompleted(message: IncomingMessage) = Unit

            override suspend fun markFailed(message: IncomingMessage) = Unit
        }
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        emptyList()

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
        lastMessageId = "message-1",
        sessionFiles = emptyList(),
        aiModelProfile = AiModelProfile.NORMAL,
    )
