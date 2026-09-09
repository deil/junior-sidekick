package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatConversationKind
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.chat.InboundMessagesQueue
import com.github.uncomplexco.sidekick.application.chat.ReplyAttachment
import com.github.uncomplexco.sidekick.application.chat.TurnStats
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.ConversationManager
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.InboundMessageFilter
import com.github.uncomplexco.sidekick.application.turn.LlmReplyDecisionClassifier
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionService
import com.github.uncomplexco.sidekick.application.turn.SimpleReplyDecisionClassifier
import com.github.uncomplexco.sidekick.application.turn.TurnExecutor
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnResult
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnRunner
import com.github.uncomplexco.sidekick.application.turn.koog.AgentTurnStats
import com.github.uncomplexco.sidekick.application.turn.koog.AgentUsageStats
import com.github.uncomplexco.sidekick.usecases.HandleIncomingChatMessageUsecase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordAdapterTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `ingress maps one text dm with real ids`() =
        runBlocking {
            val received = mutableListOf<Pair<ChatConversationId, InboundMessage>>()
            val ingress = DiscordIngress { conversation, message, _ -> received += conversation to message }

            ingress.receive(message(), "999", ::sent)

            val (conversation, incoming) = received.single()
            assertEquals("123456789012345678", conversation.channelId)
            assertEquals(ChatPlatform.DISCORD, conversation.platform)
            assertEquals(ChatConversationKind.DIRECT_MESSAGE, conversation.kind)
            assertEquals(null, conversation.threadId)
            assertEquals("234567890123456789", incoming.id)
            assertEquals("345678901234567890", incoming.sender.username)
            assertEquals("hello", incoming.text)
            assertEquals(ChatMessageType.ASSISTANT_MESSAGE, incoming.type)
        }

    @Test
    fun `message kind recognizes only exact direct bot mention tokens`() {
        assertEquals(DiscordMessageKind.DIRECT_MESSAGE, discordMessageKind(false, false, "hello", "999"))
        assertEquals(DiscordMessageKind.GUILD_MENTION, discordMessageKind(true, false, "hello <@999>", "999"))
        assertEquals(DiscordMessageKind.GUILD_MENTION, discordMessageKind(true, false, "hello <@!999>", "999"))
        assertEquals(DiscordMessageKind.GUILD_THREAD_MESSAGE, discordMessageKind(true, true, "hello", "999"))
        assertEquals(DiscordMessageKind.IGNORED_GUILD_MESSAGE, discordMessageKind(true, false, "hello <@998>", "999"))
        assertEquals(DiscordMessageKind.IGNORED_GUILD_MESSAGE, discordMessageKind(true, false, "hello <@&999>", "999"))
        assertEquals(DiscordMessageKind.IGNORED_GUILD_MESSAGE, discordMessageKind(true, false, "hello @Sidekick", "999"))
        assertEquals(DiscordMessageKind.IGNORED_GUILD_MESSAGE, discordMessageKind(true, false, "hello", "999"))
    }

    @Test
    fun `ingress maps guild mention to isolated channel turn with real ids`() =
        runBlocking {
            val received = mutableListOf<Pair<ChatConversationId, InboundMessage>>()
            val ingress = DiscordIngress { conversation, message, _ -> received += conversation to message }

            ingress.receive(message(kind = DiscordMessageKind.GUILD_MENTION, text = "hello <@999>"), "999", ::sent)

            val (conversation, incoming) = received.single()
            assertEquals("123456789012345678", conversation.channelId)
            assertEquals(ChatPlatform.DISCORD, conversation.platform)
            assertEquals(ChatConversationKind.CHANNEL, conversation.kind)
            assertEquals(null, conversation.threadId)
            assertEquals("234567890123456789", incoming.id)
            assertEquals(ChatMessageType.EXPLICIT_MENTION, incoming.type)
        }

    @Test
    fun `ingress maps passive thread message to its existing session`() =
        runBlocking {
            val received = mutableListOf<Pair<ChatConversationId, InboundMessage>>()
            val ingress = DiscordIngress { conversation, message, _ -> received += conversation to message }

            ingress.receive(message(kind = DiscordMessageKind.GUILD_THREAD_MESSAGE, threadId = "234567890123456789"), "999", ::sent)

            val (conversation, incoming) = received.single()
            assertEquals("123456789012345678", conversation.channelId)
            assertEquals("234567890123456789", conversation.threadId)
            assertEquals(ChatConversationKind.CHANNEL, conversation.kind)
            assertEquals(ChatMessageType.PASSIVE_MESSAGE, incoming.type)
        }

    @Test
    fun `ingress filters unsupported messages rejects attachments and deduplicates`() =
        runBlocking {
            val received = mutableListOf<String>()
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { _, message, _ -> received += message.id }
            val send: suspend (String) -> DiscordSentMessage = { text -> sent += text; DiscordSentMessage("1", 1) }

            ingress.receive(message(id = "bot", isBot = true), "999", send)
            ingress.receive(message(id = "webhook", isWebhook = true), "999", send)
            ingress.receive(message(id = "blank", text = "  "), "999", send)
            ingress.receive(message(id = "file", attachmentNames = listOf("report.csv")), "999", send)
            ingress.receive(message(id = "duplicate"), "999", send)
            ingress.receive(message(id = "duplicate"), "999", send)

            assertEquals(listOf("duplicate"), received)
            assertEquals(listOf("Attachments are not supported yet. Send a text-only message."), sent)
        }

    @Test
    fun `unmentioned guild message is ignored before attachment rejection and dedupe`() =
        runBlocking {
            val received = mutableListOf<String>()
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { _, message, _ -> received += message.id }
            val send: suspend (String) -> DiscordSentMessage = { text -> sent += text; DiscordSentMessage("1", 1) }
            val ignored = message(kind = DiscordMessageKind.IGNORED_GUILD_MESSAGE, attachmentNames = listOf("report.csv"))

            ingress.receive(ignored, "999", send)
            ingress.receive(ignored.copy(kind = DiscordMessageKind.GUILD_MENTION), "999", send)

            assertEquals(emptyList(), received)
            assertEquals(listOf("Attachments are not supported yet. Send a text-only message."), sent)
        }

    @Test
    fun `guild mention attachment rejection is deduplicated`() =
        runBlocking {
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { _, _, _ -> error("attachment must not enter turn flow") }
            val send: suspend (String) -> DiscordSentMessage = { text -> sent += text; DiscordSentMessage("1", 1) }
            val mentioned = message(kind = DiscordMessageKind.GUILD_MENTION, attachmentNames = listOf("report.csv"))

            ingress.receive(mentioned, "999", send)
            ingress.receive(mentioned, "999", send)

            assertEquals(listOf("Attachments are not supported yet. Send a text-only message."), sent)
        }

    @Test
    fun `turn reactions move from processing to completed`() =
        runBlocking {
            val reactions = mutableListOf<Pair<String, Boolean>>()
            val handler =
                DiscordTurnResultHandler(
                    updateReaction = { emoji, add -> reactions += emoji to add },
                    send = ::sent,
                )
            val message = InboundMessage("message", 1, MessageAuthor("user", "User"), "hello", ChatMessageType.EXPLICIT_MENTION)

            handler.markProcessing(message)
            handler.markCompleted(message)

            assertEquals(listOf("👀" to true, "👀" to false, "✅" to true), reactions)
        }

    @Test
    fun `failed turn removes processing reaction`() =
        runBlocking {
            val reactions = mutableListOf<Pair<String, Boolean>>()
            val handler =
                DiscordTurnResultHandler(
                    updateReaction = { emoji, add -> reactions += emoji to add },
                    send = ::sent,
                )
            val message = InboundMessage("message", 1, MessageAuthor("user", "User"), "hello", ChatMessageType.EXPLICIT_MENTION)

            handler.markProcessing(message)
            handler.markFailed(message)

            assertEquals(listOf("👀" to true, "👀" to false), reactions)
        }

    @Test
    fun `processing lifecycle starts one typing heartbeat and closes it`() =
        runBlocking {
            var starts = 0
            var stops = 0
            val handler =
                DiscordTurnResultHandler(
                    startTyping = {
                        starts += 1
                        AutoCloseable { stops += 1 }
                    },
                    send = ::sent,
                )
            val message = InboundMessage("message", 1, MessageAuthor("user", "User"), "hello", ChatMessageType.EXPLICIT_MENTION)

            handler.start()
            handler.markProcessing(message)
            handler.`continue`("working")
            handler.endTurn()
            handler.endTurn()

            assertEquals(1, starts)
            assertEquals(1, stops)
        }

    @Test
    fun `typing heartbeat refreshes until closed`() =
        runBlocking {
            val scope = SidekickCoroutineScope()
            val refreshed = CompletableDeferred<Unit>()
            var sends = 0
            val heartbeat =
                startDiscordTypingHeartbeat(
                    scope = scope,
                    sendTyping = {
                        sends += 1
                        if (sends == 2) refreshed.complete(Unit)
                    },
                    waitForRefresh = {
                        if (sends >= 2) awaitCancellation()
                    },
                )

            withTimeout(1_000) { refreshed.await() }
            heartbeat.close()
            scope.close()

            assertEquals(2, sends)
        }

    @Test
    fun `typing heartbeat refreshes before Discord indicator expires`() {
        assertEquals(8_000, discordTypingRefreshIntervalMs())
    }

    @Test
    fun `channel history returns a cursor only when more messages exist`() =
        runBlocking {
            val source = (101 downTo 1).map { historyMessage(it.toString()) }
            val calls = mutableListOf<Pair<String?, Int>>()

            val result =
                loadDiscordChannelHistory("channel", 100, null) { cursor, limit ->
                    calls += cursor to limit
                    val offset = cursor?.let { id -> source.indexOfFirst { it.id == id } + 1 } ?: 0
                    source.drop(offset).take(limit)
                }

            assertEquals(listOf(null to 100, "2" to 1), calls)
            assertEquals((101 downTo 2).map(Int::toString), result.messages.map { it.id })
            assertEquals("2", result.nextCursor)
        }

    @Test
    fun `reply sends complete text in 2000 character chunks and returns last real metadata`() =
        runBlocking {
            val chunks = mutableListOf<String>()
            val handler =
                DiscordTurnResultHandler { text ->
                    chunks += text
                    DiscordSentMessage("reply-${chunks.size}", 1_000L + chunks.size)
                }

            val result = handler.postReply(ChatReply("x".repeat(4_001)))

            assertEquals(listOf(2_000, 2_000, 1), chunks.map { it.length })
            assertEquals("x".repeat(4_001), chunks.joinToString(""))
            assertEquals("reply-3", result.messageId)
            assertEquals(1_003, result.timestamp)
        }

    @Test
    fun `reply does not split an emoji surrogate pair`() =
        runBlocking {
            val chunks = mutableListOf<String>()
            val handler =
                DiscordTurnResultHandler { text ->
                    chunks += text
                    DiscordSentMessage("reply", 1)
                }

            handler.postReply(ChatReply("x".repeat(1_999) + "😀"))

            assertEquals(listOf(1_999, 2), chunks.map(String::length))
            assertEquals("x".repeat(1_999) + "😀", chunks.joinToString(""))
        }

    @Test
    fun `reply names unsupported generated attachments`() =
        runBlocking {
            val path = dir.resolve("report.csv")
            Files.writeString(path, "report")
            val sent = mutableListOf<String>()
            val handler = DiscordTurnResultHandler { text -> sent += text; DiscordSentMessage("reply", 1) }

            handler.postReply(ChatReply("Ready.", listOf(ReplyAttachment(path, "report.csv", "text/csv", 6))))

            assertEquals("Ready.\n\nGenerated attachments are not supported on Discord: report.csv", sent.single())
        }

    @Test
    fun `reply renders explicit status line as Discord subtext`() =
        runBlocking {
            val sent = mutableListOf<String>()
            val handler = DiscordTurnResultHandler { text -> sent += text; DiscordSentMessage("reply", 1) }

            handler.postReply(
                ChatReply("Try again.", statusLine = "`[runtime failure]`"),
                TurnStats("normal", 65, 2, 1_200, 340),
            )

            assertEquals("Try again.\n\n-# `[runtime failure]`", sent.single())
        }

    @Test
    fun `reply renders turn stats as Discord subtext`() =
        runBlocking {
            val sent = mutableListOf<String>()
            val handler = DiscordTurnResultHandler { text -> sent += text; DiscordSentMessage("reply", 1) }

            handler.postReply(ChatReply("Ready."), TurnStats("normal", 65, 2, 1_200, 340))

            assertEquals("Ready.\n\n-# normal · 1m 5s · 1.2K → 340 · 2 tools", sent.single())
        }

    @Test
    fun `normalized discord ingress runs existing turn flow and sends observable reply`() =
        runBlocking {
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            val store = FilesystemConversationStateStore(config, ChatPlatform.DISCORD)
            val manager =
                ConversationManager(
                    store,
                    VirtualPathsFactory(config, ChatPlatform.DISCORD),
                    SessionContextCompactor { _, _, messages -> "summary for ${messages.size} messages" },
                )
            val executor =
                TurnExecutor(
                    InboundMessageFilter(manager),
                    manager,
                    ReplyDecisionService(
                        SimpleReplyDecisionClassifier(),
                        LlmReplyDecisionClassifier(koogConfig()) { _, _ -> error("classifier should not run for a DM") },
                    ),
                    config,
                    AgentTurnRunner { _, message, _ ->
                        assertEquals("hello", message.text)
                        AgentTurnResult(ChatReply("hello from Sidekick"), AgentTurnStats("normal", AgentUsageStats()))
                    },
                    { SkillCatalog(emptyList()) },
                )
            val scope = SidekickCoroutineScope()
            val usecase = HandleIncomingChatMessageUsecase(config, InboundMessagesQueue(scope, executor), executor)
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { conversation, message, chat -> usecase.handleNow(conversation, message, chat) }

            ingress.receive(message(), "999") { text ->
                sent += text
                DiscordSentMessage("456789012345678901", 1_789_000_001)
            }

            val id = ConversationId("123456789012345678", "")
            val state = store.load(id)
            assertTrue(sent.single().startsWith("hello from Sidekick\n\n-# normal · "))
            assertEquals(listOf(SessionMessageRole.USER, SessionMessageRole.ASSISTANT), state.messages.map { it.role })
            assertEquals("456789012345678901", state.messages.last().id)
            assertFalse(Files.exists(dir.resolve("state/slack/channels/123456789012345678")))
            scope.close()
        }

    @Test
    fun `normalized guild mention runs existing turn flow in isolated root conversation`() =
        runBlocking {
            val config = AgentConfig("Sidekick", dir.resolve("state").toString(), dir.resolve("workspace").toString())
            val store = FilesystemConversationStateStore(config, ChatPlatform.DISCORD)
            val manager =
                ConversationManager(
                    store,
                    VirtualPathsFactory(config, ChatPlatform.DISCORD),
                    SessionContextCompactor { _, _, messages -> "summary for ${messages.size} messages" },
                )
            val executor =
                TurnExecutor(
                    InboundMessageFilter(manager),
                    manager,
                    ReplyDecisionService(
                        SimpleReplyDecisionClassifier(),
                        LlmReplyDecisionClassifier(koogConfig()) { _, _ -> error("classifier should not run for a mention") },
                    ),
                    config,
                    AgentTurnRunner { _, message, _ ->
                        assertEquals("hello <@999>", message.text)
                        AgentTurnResult(ChatReply("guild reply"), AgentTurnStats("normal", AgentUsageStats()))
                    },
                    { SkillCatalog(emptyList()) },
                )
            val scope = SidekickCoroutineScope()
            val usecase = HandleIncomingChatMessageUsecase(config, InboundMessagesQueue(scope, executor), executor)
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { conversation, message, chat -> usecase.handleNow(conversation, message, chat) }

            ingress.receive(message(kind = DiscordMessageKind.GUILD_MENTION, text = "hello <@999>"), "999") { text ->
                sent += text
                DiscordSentMessage("456789012345678901", 1_789_000_001)
            }

            val id = ConversationId("123456789012345678", "234567890123456789")
            val state = store.load(id)
            assertTrue(sent.single().startsWith("guild reply\n\n-# normal · "))
            assertEquals(listOf(SessionMessageRole.USER, SessionMessageRole.ASSISTANT), state.messages.map { it.role })
            assertEquals("456789012345678901", state.messages.last().id)
            scope.close()
        }

    private fun message(
        id: String = "234567890123456789",
        text: String = " hello ",
        attachmentNames: List<String> = emptyList(),
        kind: DiscordMessageKind = DiscordMessageKind.DIRECT_MESSAGE,
        threadId: String? = null,
        isBot: Boolean = false,
        isWebhook: Boolean = false,
    ) = DiscordIncomingMessage(
        id = id,
        channelId = "123456789012345678",
        createdAtMs = 1_789_000_000,
        authorId = "345678901234567890",
        authorName = "User",
        text = text,
        attachmentNames = attachmentNames,
        kind = kind,
        threadId = threadId,
        isBot = isBot,
        isWebhook = isWebhook,
    )

    private fun sent(text: String): DiscordSentMessage = DiscordSentMessage("sent", text.length.toLong())

    private fun historyMessage(id: String) =
        DiscordChannelHistoryMessage(id, "2026-09-09T10:00:00Z", "user", "User", false, "message $id", null)

    private fun koogConfig() =
        KoogConfig(
            openRouterApiKey = "test-key",
            openRouterAppTitle = "Sidekick",
            openRouterAppUrl = "",
            fastModel = "openai/gpt-5.4-mini",
            fastProvider = "azure",
            fastReasoningEffort = "low",
            defaultModel = "z-ai/glm-5.2",
            defaultProvider = "azure",
            defaultReasoningEffort = "medium",
            ultrathinkModel = "openai/gpt-5.5",
            ultrathinkProvider = "azure",
            ultrathinkReasoningEffort = "high",
            imageModel = "image-model",
            maxAgentIterations = 50,
        )
}
