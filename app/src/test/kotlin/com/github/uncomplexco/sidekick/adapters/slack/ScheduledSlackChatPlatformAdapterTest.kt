package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.ChatReply
import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledSlackChatPlatformAdapterTest {
    @Test
    fun `posts scheduled reply to channel root`() =
        runBlocking {
            // Arrange
            val requests = mutableListOf<ChatPostMessageRequest>()
            val client = methodsClient(requests)
            val adapter = ScheduledSlackChatPlatformAdapter(client, "C123", "USIDEKICK")

            // Act
            adapter.resultHandler.postReply(ChatReply("scheduled result"))

            // Assert
            assertEquals("C123", requests.single().channel)
            assertEquals("scheduled result", requests.single().text)
            assertNull(requests.single().threadTs)
        }

    @Suppress("UNCHECKED_CAST")
    private fun methodsClient(requests: MutableList<ChatPostMessageRequest>): MethodsClient =
        Proxy.newProxyInstance(
            MethodsClient::class.java.classLoader,
            arrayOf(MethodsClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "chatPostMessage" -> {
                    val configure = args!![0] as RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>
                    requests += configure.configure(ChatPostMessageRequest.builder()).build()
                    ChatPostMessageResponse().also {
                        it.isOk = true
                        it.ts = "1700000000.000"
                    }
                }

                else -> error("Unexpected Slack method: ${method.name}")
            }
        } as MethodsClient
}
