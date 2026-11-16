package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.slack.api.RequestConfigurator
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest
import com.slack.api.methods.request.users.UsersInfoRequest
import com.slack.api.methods.response.auth.AuthTestResponse
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse
import com.slack.api.methods.response.users.UsersInfoResponse
import com.slack.api.model.BotProfile
import com.slack.api.model.Message
import com.slack.api.model.User
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SlackThreadHistoryTest {
    private val client = mock(MethodsClient::class.java)
    private val ctx = mock(EventContext::class.java)
    private val fileIngestor = mock(SlackFileIngestor::class.java)
    private val conversationId = ConversationId("C123", "100.000001")

    init {
        `when`(ctx.client()).thenReturn(client)
        `when`(ctx.botUserId).thenReturn("USIDEKICK")
        `when`(ctx.botId).thenReturn("BSIDEKICK")
        runBlocking {
            `when`(fileIngestor.ingest(conversationId, emptyList(), summarizeImages = false))
                .thenReturn(emptyList())
        }
    }

    @Test
    fun `bot without a user is retained with its profile name and bot identity`() = runTest {
        val message =
            message().apply {
                botId = "BOTHER"
                botProfile = BotProfile().apply { name = "Deploy Bot" }
            }
        replies(message)

        val history = loadHistory()

        assertEquals(SessionMessageRole.USER, history.single().role)
        assertEquals(MessageAuthor("BOTHER", "Deploy Bot"), history.single().author)
        assertEquals("Deployment finished", history.single().text)
        verify(client, never())
            .usersInfo(any<RequestConfigurator<UsersInfoRequest.UsersInfoRequestBuilder>>())
    }

    @Test
    fun `legacy bot without a profile uses its message username`() = runTest {
        replies(
            message().apply {
                botId = "BOTHER"
                username = "webhook"
            }
        )

        val history = loadHistory()

        assertEquals(MessageAuthor("BOTHER", "webhook"), history.single().author)
    }

    @Test
    fun `bot without a profile or username falls back to its bot ID`() = runTest {
        replies(message().apply { botId = "BOTHER" })

        val history = loadHistory()

        assertEquals(MessageAuthor("BOTHER", "BOTHER"), history.single().author)
    }

    @Test
    fun `other bot with a user ID retains that user identity`() = runTest {
        replies(
            message().apply {
                botId = "BOTHER"
                user = "UOTHER"
                botProfile = BotProfile().apply { name = "Deploy Bot" }
            }
        )

        val history = loadHistory()

        assertEquals(SessionMessageRole.USER, history.single().role)
        assertEquals(MessageAuthor("UOTHER", "Deploy Bot"), history.single().author)
    }

    @Test
    fun `Sidekick message without a user is recognized by bot ID`() = runTest {
        replies(message().apply { botId = "BSIDEKICK" })

        val history = loadHistory()

        assertEquals(SessionMessageRole.ASSISTANT, history.single().role)
        assertNull(history.single().author)
    }

    @Test
    fun `single workspace Sidekick message is recognized using the auth test bot ID`() = runTest {
        `when`(ctx.botId).thenReturn(null)
        `when`(ctx.authTestResponse).thenReturn(AuthTestResponse().apply { botId = "BSIDEKICK" })
        replies(message().apply { botId = "BSIDEKICK" })

        val history = loadHistory()

        assertEquals(SessionMessageRole.ASSISTANT, history.single().role)
        assertNull(history.single().author)
    }

    @Test
    fun `single workspace history retains another bot as an external author`() = runTest {
        `when`(ctx.botId).thenReturn(null)
        `when`(ctx.authTestResponse).thenReturn(AuthTestResponse().apply { botId = "BSIDEKICK" })
        replies(
            message().apply {
                botId = "BOTHER"
                botProfile = BotProfile().apply { name = "Deploy Bot" }
            }
        )

        val history = loadHistory()

        assertEquals(SessionMessageRole.USER, history.single().role)
        assertEquals(MessageAuthor("BOTHER", "Deploy Bot"), history.single().author)
    }

    @Test
    fun `Sidekick message is recognized by user ID without a bot ID`() = runTest {
        replies(message().apply { user = "USIDEKICK" })

        val history = loadHistory()

        assertEquals(SessionMessageRole.ASSISTANT, history.single().role)
        assertNull(history.single().author)
    }

    @Test
    fun `human history still resolves its author through Slack user lookup`() = runTest {
        replies(message().apply { user = "UHISTORY" })
        `when`(
                client.usersInfo(
                    any<RequestConfigurator<UsersInfoRequest.UsersInfoRequestBuilder>>()
                )
            )
            .thenReturn(
                UsersInfoResponse().apply {
                    isOk = true
                    user =
                        User().apply {
                            realName = "Alice"
                            profile = User.Profile().apply { displayNameNormalized = "alice" }
                        }
                }
            )

        val history = loadHistory()

        assertEquals(SessionMessageRole.USER, history.single().role)
        assertEquals(MessageAuthor("UHISTORY", "alice"), history.single().author)
    }

    private fun message() =
        Message().apply {
            ts = "100.000001"
            text = "Deployment finished"
        }

    private fun replies(vararg messages: Message) {
        `when`(
                client.conversationsReplies(
                    any<
                        RequestConfigurator<
                            ConversationsRepliesRequest.ConversationsRepliesRequestBuilder
                        >
                    >()
                )
            )
            .thenReturn(
                ConversationsRepliesResponse().apply {
                    isOk = true
                    this.messages = messages.toList()
                }
            )
    }

    private suspend fun loadHistory() =
        loadThreadHistory(ctx, "100.000001", "100.000002", conversationId, fileIngestor)
}
