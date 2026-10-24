package com.github.uncomplexco.sidekick.application.agent.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentSteering
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.LlmProfile
import com.github.uncomplexco.sidekick.application.agent.ReasoningEffort
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ExplicitSkillInvocation
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.turn.RecordedTurn
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.checkpoints.TurnCheckpointPersistence
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`

class SidekickAgentSteeringTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `agent returns stopped without another model request before tools or after running tools`(
        duringTools: Boolean
    ) = runBlocking {
        // Arrange
        val config =
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString()).also {
                it.botUsername = "bot"
            }
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val sessions =
            SessionManager(
                store,
                VirtualPathsFactory(config, ChatPlatform.SLACK),
                SessionContextCompactor { _, _, _ -> "summary" },
            )
        val conversation = ConversationId("C123", "thread")
        val turn = recordInitial(sessions, conversation, false)
        val registry = mock(McpServersRegistry::class.java)
        `when`(
                registry.connect(
                    conversation,
                    "alice",
                    turn.context.conversation.virtualPaths.workRoot,
                )
            )
            .thenReturn(emptyList())
        var requested = false
        val targets = mutableListOf<Pair<String, List<String>>>()
        val tools = ContextTools(targets) { requested = true }
        val executor =
            QueuedPromptExecutor(
                toolCall("call-1", "call-2"),
                Message.Assistant("Finished.", ResponseMetaInfo.Empty),
                beforeResponse = { if (!duringTools) requested = true },
            )
        val koog = modelConfig(turn.context)
        val agent = agent(config, koog, store, tools, registry)
        val steering =
            object : AgentSteering {
                override fun stopRequested() = requested

                override suspend fun drain(): RecordedTurn? = null
            }

        // Act
        val result =
            mockStatic(
                    Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
                )
                .use { model ->
                    model.`when`<PromptExecutor> { koog.openRouterExecutor() }.thenReturn(executor)
                    agent.execute(turn, mock(ChatPlatformAdapter::class.java), steering)
                }

        // Assert
        assertIs<TurnExecutionResult.Stopped>(result)
        assertEquals(
            if (duringTools) List(2) { "m2" to emptyList<String>() } else emptyList(),
            targets,
        )
        assertEquals(1, executor.prompts.size)
        assertEquals(if (duringTools) 2 else 0, result.stats.usage.toolCallCount)
        assertStoppedHistory(store.load(conversation).koogMessages)
        assertNextTurnSeesInterruption(agent, sessions, koog, executor, conversation)
    }

    private suspend fun assertNextTurnSeesInterruption(
        agent: SidekickAgent,
        sessions: SessionManager,
        koog: KoogConfig,
        executor: QueuedPromptExecutor,
        conversation: ConversationId,
    ) {
        val next =
            sessions.recordIncomingMessages(
                conversation,
                false,
                { emptyList() },
                listOf(message("m3", "alice", text = "What is two plus two?")),
                emptyList(),
            )
        val result =
            mockStatic(
                    Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt")
                )
                .use { model ->
                    model.`when`<PromptExecutor> { koog.openRouterExecutor() }.thenReturn(executor)
                    agent.execute(next, mock(ChatPlatformAdapter::class.java)) { null }
                }
        assertIs<TurnExecutionResult.Completed>(result)
        val sent = executor.prompts[1].messages
        assertStoppedHistory(sent.dropLast(1))
        assertTrue(sent.last().textContent().contains("What is two plus two?"))
    }

    private fun assertStoppedHistory(messages: List<Message>) {
        val results = messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
        assertEquals(listOf("call-1", "call-2"), results.map { it.id })
        assertTrue(results.all { it.isError })
        assertTrue(results.all { it.output == "Tool execution was interrupted by the user." })
        assertEquals(
            1,
            messages.filterIsInstance<Message.Assistant>().size,
            "No synthetic assistant acknowledgement",
        )
        assertEquals(
            2,
            messages.filterIsInstance<Message.User>().count { user ->
                user.parts.none { it is MessagePart.Tool.Result }
            },
            "No synthetic user instruction",
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `steering preserves original identity and updates live tool targets files and individual prompts`(
        multipleAuthors: Boolean
    ) = runBlocking {
        // Arrange
        val config =
            AgentConfig("Sidekick", dir.resolve("state").toString(), dir.toString()).also {
                it.botUsername = "bot"
            }
        val store = FilesystemConversationStateStore(config, ChatPlatform.SLACK)
        val sessions =
            SessionManager(
                store,
                VirtualPathsFactory(config, ChatPlatform.SLACK),
                SessionContextCompactor { _, _, _ -> "summary" },
            )
        val conversation = ConversationId("C123", "thread")
        val turn = recordInitial(sessions, conversation, multipleAuthors)
        val registry = mock(McpServersRegistry::class.java)
        val requester = if (multipleAuthors) null else "alice"
        `when`(
                registry.connect(
                    conversation,
                    requester,
                    turn.context.conversation.virtualPaths.workRoot,
                )
            )
            .thenReturn(emptyList())
        val targets = mutableListOf<Pair<String, List<String>>>()
        val tools = ContextTools(targets)
        val executor =
            QueuedPromptExecutor(
                toolCall("call-1"),
                toolCall("call-2"),
                Message.Assistant("Finished.", ResponseMetaInfo.Empty),
            )
        val koog = modelConfig(turn.context)
        val agent = agent(config, koog, store, tools, registry)
        var drains = 0

        // Act: replace only the model executor; run the actual agent, tools, memory, and
        // persistence.
        mockStatic(Class.forName("com.github.uncomplexco.sidekick.application.agent.OpenRouterKt"))
            .use { model ->
                model.`when`<PromptExecutor> { koog.openRouterExecutor() }.thenReturn(executor)
                agent.execute(turn, mock(ChatPlatformAdapter::class.java)) {
                    if (drains++ == 0)
                        sessions.appendIncomingMessages(
                            turn,
                            listOf(message("m3", "charlie", withAttachmentAndSkill = true)),
                            listOf(file()),
                        )
                    else null
                }
            }

        // Assert
        assertEquals(2, drains)
        verify(registry)
            .connect(conversation, requester, turn.context.conversation.virtualPaths.workRoot)
        verifyNoMoreInteractions(registry)
        assertEquals(listOf("m2" to emptyList(), "m3" to listOf("f3")), targets)
        assertSteeringPrompts(executor, multipleAuthors)
        assertTrue(
            store.load(conversation).koogMessages.any {
                it.textContent().contains("[charlie] request m3")
            }
        )
    }

    private fun assertSteeringPrompts(executor: QueuedPromptExecutor, multipleAuthors: Boolean) {
        val users =
            executor.prompts[1].messages.filterIsInstance<Message.User>().filter {
                it.parts.none { part -> part is MessagePart.Tool.Result }
            }
        assertEquals(3, users.size)
        val steering = users.last().textContent()
        assertTrue(steering.contains("[charlie] request m3"))
        assertTrue(steering.contains("/review"))
        assertTrue(steering.contains("<attachment id=\"f3\">"))
        assertFalse(steering.contains("<requester>"))
        assertFalse(steering.contains("<runtime_context>"))
        assertEquals(
            if (multipleAuthors) 0 else 1,
            users.count { it.textContent().contains("<requester>") },
        )
        assertEquals(
            3,
            executor.prompts.last().messages.filterIsInstance<Message.User>().count {
                it.parts.none { part -> part is MessagePart.Tool.Result }
            },
        )
    }

    private suspend fun recordInitial(
        sessions: SessionManager,
        conversation: ConversationId,
        multipleAuthors: Boolean,
    ): RecordedTurn =
        sessions.recordIncomingMessages(
            conversation,
            false,
            { emptyList() },
            listOf(message("m1", "alice"), message("m2", if (multipleAuthors) "bob" else "alice")),
            emptyList(),
        )

    private fun agent(
        config: AgentConfig,
        koog: KoogConfig,
        store: FilesystemConversationStateStore,
        tools: ToolRegistryFactory,
        registry: McpServersRegistry,
    ) =
        SidekickAgent(
            config,
            koog,
            SystemPromptBuilder(config, ChatPlatform.SLACK),
            TurnPromptBuilder(config) { SkillCatalog(emptyList()) },
            tools,
            registry,
            ConversationStateChatHistoryProvider(store),
            TurnCheckpointPersistence(store),
        )

    private fun modelConfig(ctx: TurnContext): KoogConfig {
        val config = mock(KoogConfig::class.java)
        val profile = LlmProfile("test", "test", ReasoningEffort.LOW)
        `when`(config.profile(ctx.aiModelProfile)).thenReturn(profile)
        `when`(config.modelCapabilities()).thenReturn(listOf(LLMCapability.Tools))
        `when`(config.openRouterParams(profile, ctx.conversation.conversationId))
            .thenReturn(OpenRouterParams())
        `when`(config.maxAgentIterations).thenReturn(20)
        return config
    }

    private fun message(
        id: String,
        author: String,
        withAttachmentAndSkill: Boolean = false,
        text: String = "request $id",
    ) =
        SessionMessage(
            id,
            SessionMessageRole.USER,
            MessageAuthor(author, author),
            text,
            createdAtMs = id.drop(1).toLong(),
            fileIds = if (withAttachmentAndSkill) listOf("f3") else emptyList(),
            explicitSkillInvocation =
                if (withAttachmentAndSkill) ExplicitSkillInvocation("review") else null,
        )

    private fun file() =
        IncomingChatFile(
            "f3",
            "report.txt",
            "text/plain",
            "txt",
            "https://files/report",
            "https://chat/report",
            "/data/report.txt",
        )

    private fun toolCall(vararg ids: String) =
        Message.Assistant(
            parts = ids.map { MessagePart.Tool.Call(it, "inspect", "{}") },
            metaInfo = ResponseMetaInfo.Empty,
        )

    private class ContextTools(
        private val targets: MutableList<Pair<String, List<String>>>,
        private val onInspected: () -> Unit = {},
    ) : ToolRegistryFactory {
        override suspend fun buildExecutionTools(
            ctx: TurnContext,
            chat: ChatPlatformAdapter,
            replyAttachments: ReplyAttachmentCollector,
            onSubagentCompleted: (AgentUsageStats) -> Unit,
        ) = ToolRegistry { tool(InspectTool(ctx, targets, onInspected)) }

        override suspend fun buildOrchestrationTools(
            toolRegistry: ToolRegistry,
            chat: ChatPlatformAdapter,
            ctx: TurnContext,
        ) = ToolRegistry.EMPTY
    }

    private class InspectTool(
        private val ctx: TurnContext,
        private val targets: MutableList<Pair<String, List<String>>>,
        private val onInspected: () -> Unit,
    ) : SimpleTool<InspectTool.Args>(typeToken<Args>(), "inspect", "Inspect the current input") {
        @Serializable class Args

        override suspend fun execute(args: Args): String {
            targets += ctx.lastMessageId to ctx.sessionFiles.map { it.id }
            onInspected()
            return "inspected"
        }
    }
}
