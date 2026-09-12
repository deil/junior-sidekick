package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatConversationKind
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.chat.DiscordChannelHistoryMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.IncomingMessageQueue
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachment
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.stats.TurnStats
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.TurnAdmission
import com.github.uncomplexco.sidekick.application.turn.DeterministicParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.LlmParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.TurnParticipation
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.usecases.ReceiveChatMessageUsecase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscordAdapterTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `ingress maps one text dm with real ids`() = runBlocking {
        val received = mutableListOf<Pair<ChatConversationId, IncomingMessage>>()
        val ingress = DiscordIngress { conversationId, message, _ ->
            received += conversationId to message
        }

        ingress.receive(message(), "999", ::sent)

        val (conversation, incoming) = received.single()
        assertEquals("123456789012345678", conversation.channelId)
        assertEquals(ChatPlatform.DISCORD, conversation.platform)
        assertEquals(ChatConversationKind.DIRECT_MESSAGE, conversation.kind)
        assertEquals(null, conversation.threadId)
        assertEquals("234567890123456789", incoming.id)
        assertEquals("345678901234567890", incoming.sender.username)
        assertEquals("hello", incoming.text)
        assertEquals(IncomingMessageType.ASSISTANT_MESSAGE, incoming.type)
    }

    @Test
    fun `message kind recognizes only exact direct bot mention tokens`() {
        assertEquals(
            DiscordMessageKind.DIRECT_MESSAGE,
            discordMessageKind(false, false, "hello", "999"),
        )
        assertEquals(
            DiscordMessageKind.GUILD_MENTION,
            discordMessageKind(true, false, "hello <@999>", "999"),
        )
        assertEquals(
            DiscordMessageKind.GUILD_MENTION,
            discordMessageKind(true, false, "hello <@!999>", "999"),
        )
        assertEquals(
            DiscordMessageKind.GUILD_THREAD_MESSAGE,
            discordMessageKind(true, true, "hello", "999"),
        )
        assertEquals(
            DiscordMessageKind.IGNORED_GUILD_MESSAGE,
            discordMessageKind(true, false, "hello <@998>", "999"),
        )
        assertEquals(
            DiscordMessageKind.IGNORED_GUILD_MESSAGE,
            discordMessageKind(true, false, "hello <@&999>", "999"),
        )
        assertEquals(
            DiscordMessageKind.IGNORED_GUILD_MESSAGE,
            discordMessageKind(true, false, "hello @Sidekick", "999"),
        )
        assertEquals(
            DiscordMessageKind.IGNORED_GUILD_MESSAGE,
            discordMessageKind(true, false, "hello", "999"),
        )
    }

    @Test
    fun `ingress maps guild mention to isolated channel turn with real ids`() = runBlocking {
        val received = mutableListOf<Pair<ChatConversationId, IncomingMessage>>()
        val ingress = DiscordIngress { conversationId, message, _ ->
            received += conversationId to message
        }

        ingress.receive(
            message(kind = DiscordMessageKind.GUILD_MENTION, text = "hello <@999>"),
            "999",
            ::sent,
        )

        val (conversation, incoming) = received.single()
        assertEquals("123456789012345678", conversation.channelId)
        assertEquals(ChatPlatform.DISCORD, conversation.platform)
        assertEquals(ChatConversationKind.CHANNEL, conversation.kind)
        assertEquals(null, conversation.threadId)
        assertEquals("234567890123456789", incoming.id)
        assertEquals(IncomingMessageType.EXPLICIT_MENTION, incoming.type)
    }

    @Test
    fun `ingress maps passive thread message to its existing session`() = runBlocking {
        val received = mutableListOf<Pair<ChatConversationId, IncomingMessage>>()
        val ingress = DiscordIngress { conversationId, message, _ ->
            received += conversationId to message
        }

        ingress.receive(
            message(
                kind = DiscordMessageKind.GUILD_THREAD_MESSAGE,
                threadId = "234567890123456789",
            ),
            "999",
            ::sent,
        )

        val (conversation, incoming) = received.single()
        assertEquals("123456789012345678", conversation.channelId)
        assertEquals("234567890123456789", conversation.threadId)
        assertEquals(ChatConversationKind.CHANNEL, conversation.kind)
        assertEquals(IncomingMessageType.PASSIVE_MESSAGE, incoming.type)
    }

    @Test
    fun `ingress filters unsupported messages rejects attachments and deduplicates`() =
        runBlocking {
            val received = mutableListOf<String>()
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { _, message, _ -> received += message.id }
            val send: suspend (String) -> DiscordSentMessage = { text ->
                sent += text
                DiscordSentMessage("1", 1)
            }

            ingress.receive(message(id = "bot", isBot = true), "999", send)
            ingress.receive(message(id = "webhook", isWebhook = true), "999", send)
            ingress.receive(message(id = "blank", text = "  "), "999", send)
            ingress.receive(
                message(id = "file", attachmentNames = listOf("report.csv")),
                "999",
                send,
            )
            ingress.receive(message(id = "duplicate"), "999", send)
            ingress.receive(message(id = "duplicate"), "999", send)

            assertEquals(listOf("duplicate"), received)
            assertEquals(
                listOf("Attachments are not supported yet. Send a text-only message."),
                sent,
            )
        }

    @Test
    fun `unmentioned guild message is ignored before attachment rejection and dedupe`() =
        runBlocking {
            val received = mutableListOf<String>()
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { _, message, _ -> received += message.id }
            val send: suspend (String) -> DiscordSentMessage = { text ->
                sent += text
                DiscordSentMessage("1", 1)
            }
            val ignored =
                message(
                    kind = DiscordMessageKind.IGNORED_GUILD_MESSAGE,
                    attachmentNames = listOf("report.csv"),
                )

            ingress.receive(ignored, "999", send)
            ingress.receive(ignored.copy(kind = DiscordMessageKind.GUILD_MENTION), "999", send)

            assertEquals(emptyList(), received)
            assertEquals(
                listOf("Attachments are not supported yet. Send a text-only message."),
                sent,
            )
        }

    @Test
    fun `guild mention attachment rejection is deduplicated`() = runBlocking {
        val sent = mutableListOf<String>()
        val ingress = DiscordIngress { _, _, _ -> error("attachment must not enter turn flow") }
        val send: suspend (String) -> DiscordSentMessage = { text ->
            sent += text
            DiscordSentMessage("1", 1)
        }
        val mentioned =
            message(kind = DiscordMessageKind.GUILD_MENTION, attachmentNames = listOf("report.csv"))

        ingress.receive(mentioned, "999", send)
        ingress.receive(mentioned, "999", send)

        assertEquals(listOf("Attachments are not supported yet. Send a text-only message."), sent)
    }

    @Test
    fun `turn reactions move from processing to completed`() = runBlocking {
        val reactions = mutableListOf<Pair<String, Boolean>>()
        val handler =
            DiscordTurnOutput(
                updateReaction = { emoji, add -> reactions += emoji to add },
                send = ::sent,
            )
        val message =
            IncomingMessage(
                "message",
                1,
                MessageAuthor("user", "User"),
                "hello",
                IncomingMessageType.EXPLICIT_MENTION,
            )

        handler.markProcessing(message)
        handler.markCompleted(message)

        assertEquals(listOf("👀" to true, "👀" to false, "✅" to true), reactions)
    }

    @Test
    fun `failed turn removes processing reaction`() = runBlocking {
        val reactions = mutableListOf<Pair<String, Boolean>>()
        val handler =
            DiscordTurnOutput(
                updateReaction = { emoji, add -> reactions += emoji to add },
                send = ::sent,
            )
        val message =
            IncomingMessage(
                "message",
                1,
                MessageAuthor("user", "User"),
                "hello",
                IncomingMessageType.EXPLICIT_MENTION,
            )

        handler.markProcessing(message)
        handler.markFailed(message)

        assertEquals(listOf("👀" to true, "👀" to false), reactions)
    }

    @Test
    fun `processing lifecycle starts one typing heartbeat and closes it`() = runBlocking {
        var starts = 0
        var stops = 0
        val handler =
            DiscordTurnOutput(
                startTyping = {
                    starts += 1
                    AutoCloseable { stops += 1 }
                },
                send = ::sent,
            )
        val message =
            IncomingMessage(
                "message",
                1,
                MessageAuthor("user", "User"),
                "hello",
                IncomingMessageType.EXPLICIT_MENTION,
            )

        handler.begin()
        handler.markProcessing(message)
        handler.progress("working")
        handler.end()
        handler.end()

        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    @Test
    fun `typing heartbeat refreshes until closed`() = runBlocking {
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
                waitForRefresh = { if (sends >= 2) awaitCancellation() },
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
    fun `channel history returns a cursor only when more messages exist`() = runBlocking {
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
            val handler = DiscordTurnOutput { text ->
                chunks += text
                DiscordSentMessage("reply-${chunks.size}", 1_000L + chunks.size)
            }

            val result = handler.deliver(AgentResponse("x".repeat(4_001)))

            assertEquals(listOf(2_000, 2_000, 1), chunks.map { it.length })
            assertEquals("x".repeat(4_001), chunks.joinToString(""))
            assertEquals("reply-3", result.messageId)
            assertEquals(1_003, result.timestamp)
        }

    @Test
    fun `reply does not split an emoji surrogate pair`() = runBlocking {
        val chunks = mutableListOf<String>()
        val handler = DiscordTurnOutput { text ->
            chunks += text
            DiscordSentMessage("reply", 1)
        }

        handler.deliver(AgentResponse("x".repeat(1_999) + "😀"))

        assertEquals(listOf(1_999, 2), chunks.map(String::length))
        assertEquals("x".repeat(1_999) + "😀", chunks.joinToString(""))
    }

    @Test
    fun `reply names unsupported generated attachments`() = runBlocking {
        val path = dir.resolve("report.csv")
        Files.writeString(path, "report")
        val sent = mutableListOf<String>()
        val handler = DiscordTurnOutput { text ->
            sent += text
            DiscordSentMessage("reply", 1)
        }

        handler.deliver(
            AgentResponse("Ready.", listOf(ReplyAttachment(path, "report.csv", "text/csv", 6)))
        )

        assertEquals(
            "Ready.\n\nGenerated attachments are not supported on Discord: report.csv",
            sent.single(),
        )
    }

    @Test
    fun `reply renders explicit status line as Discord subtext`() = runBlocking {
        val sent = mutableListOf<String>()
        val handler = DiscordTurnOutput { text ->
            sent += text
            DiscordSentMessage("reply", 1)
        }

        handler.deliver(
            AgentResponse("Try again."),
            ResponseMetadata.RuntimeFailure,
        )

        assertEquals("Try again.\n\n-# `[runtime failure]`", sent.single())
    }

    @Test
    fun `reply renders turn stats as Discord subtext`() = runBlocking {
        val sent = mutableListOf<String>()
        val handler = DiscordTurnOutput { text ->
            sent += text
            DiscordSentMessage("reply", 1)
        }

        handler.deliver(
            AgentResponse("Ready."),
            ResponseMetadata.Turn(TurnStats("normal", 65, 2, 1_200, 340)),
        )

        assertEquals("Ready.\n\n-# normal · 1m 5s · 1.2K → 340 · 2 tools", sent.single())
    }

    @Test
    fun `marker-only reply with stats is not sent`() = runBlocking {
        var sends = 0
        val handler = DiscordTurnOutput {
            sends += 1
            DiscordSentMessage("reply", 1)
        }

        val result =
            handler.deliver(
                AgentResponse(SystemPromptBuilder.NO_REPLY_MARKER),
                ResponseMetadata.Turn(TurnStats("normal", 65, 2, 1_200, 340)),
            )

        assertEquals(0, sends)
        assertEquals("no_reply:${result.timestamp}", result.messageId)
    }

    @Test
    fun `normalized discord ingress runs existing turn flow and sends observable reply`() =
        runBlocking {
            val config =
                AgentConfig(
                    "Sidekick",
                    dir.resolve("state").toString(),
                    dir.resolve("workspace").toString(),
                )
            val store = FilesystemConversationStateStore(config, ChatPlatform.DISCORD)
            val manager =
                SessionManager(
                    store,
                    VirtualPathsFactory(config, ChatPlatform.DISCORD),
                    SessionContextCompactor { _, _, messages ->
                        "summary for ${messages.size} messages"
                    },
                )
            val coordinator =
                TurnCoordinator(
                    admission = TurnAdmission(manager),
                    sessions = manager,
                    participation = TurnParticipation(
                        config,
                        DeterministicParticipationClassifier(),
                        LlmParticipationClassifier(koogConfig()) { _, _ ->
                            error("classifier should not run for a DM")
                        },
                    ),
                    agent = AgentTurnExecutor { turn, _ ->
                        assertEquals("hello", turn.message.text)
                        TurnExecutionResult(
                            AgentResponse("hello from Sidekick"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                    },
                    skills = { SkillCatalog(emptyList()) },
                )
            val scope = SidekickCoroutineScope()
            val usecase =
                ReceiveChatMessageUsecase(config, IncomingMessageQueue(scope, coordinator), coordinator)
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { conversationId, message, chat ->
                usecase.receiveNow(conversationId, message, chat)
            }

            ingress.receive(message(), "999") { text ->
                sent += text
                DiscordSentMessage("456789012345678901", 1_789_000_001)
            }

            val id = ConversationId("123456789012345678", "")
            val state = store.load(id)
            assertTrue(sent.single().startsWith("hello from Sidekick\n\n-# normal · "))
            assertEquals(
                listOf(SessionMessageRole.USER, SessionMessageRole.ASSISTANT),
                state.messages.map { it.role },
            )
            assertEquals("456789012345678901", state.messages.last().id)
            assertFalse(Files.exists(dir.resolve("state/slack/channels/123456789012345678")))
            scope.close()
        }

    @Test
    fun `normalized guild mention runs existing turn flow in isolated root conversation`() =
        runBlocking {
            val config =
                AgentConfig(
                    "Sidekick",
                    dir.resolve("state").toString(),
                    dir.resolve("workspace").toString(),
                )
            val store = FilesystemConversationStateStore(config, ChatPlatform.DISCORD)
            val manager =
                SessionManager(
                    store,
                    VirtualPathsFactory(config, ChatPlatform.DISCORD),
                    SessionContextCompactor { _, _, messages ->
                        "summary for ${messages.size} messages"
                    },
                )
            val coordinator =
                TurnCoordinator(
                    admission = TurnAdmission(manager),
                    sessions = manager,
                    participation = TurnParticipation(
                        config,
                        DeterministicParticipationClassifier(),
                        LlmParticipationClassifier(koogConfig()) { _, _ ->
                            error("classifier should not run for a mention")
                        },
                    ),
                    agent = AgentTurnExecutor { turn, _ ->
                        assertEquals("hello <@999>", turn.message.text)
                        TurnExecutionResult(
                            AgentResponse("guild reply"),
                            AgentStats("normal", AgentUsageStats()),
                        )
                    },
                    skills = { SkillCatalog(emptyList()) },
                )
            val scope = SidekickCoroutineScope()
            val usecase =
                ReceiveChatMessageUsecase(config, IncomingMessageQueue(scope, coordinator), coordinator)
            val sent = mutableListOf<String>()
            val ingress = DiscordIngress { conversationId, message, chat ->
                usecase.receiveNow(conversationId, message, chat)
            }

            ingress.receive(
                message(kind = DiscordMessageKind.GUILD_MENTION, text = "hello <@999>"),
                "999",
            ) { text ->
                sent += text
                DiscordSentMessage("456789012345678901", 1_789_000_001)
            }

            val id = ConversationId("123456789012345678", "234567890123456789")
            val state = store.load(id)
            assertTrue(sent.single().startsWith("guild reply\n\n-# normal · "))
            assertEquals(
                listOf(SessionMessageRole.USER, SessionMessageRole.ASSISTANT),
                state.messages.map { it.role },
            )
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
    ) =
        DiscordIncomingMessage(
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

    private fun sent(text: String): DiscordSentMessage =
        DiscordSentMessage("sent", text.length.toLong())

    private fun historyMessage(id: String) =
        DiscordChannelHistoryMessage(
            id,
            "2026-09-09T10:00:00Z",
            "user",
            "User",
            false,
            "message $id",
            null,
        )

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
