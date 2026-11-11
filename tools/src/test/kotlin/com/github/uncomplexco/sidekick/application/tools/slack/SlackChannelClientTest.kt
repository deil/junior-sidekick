package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.ToolException
import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.methods.MethodsClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class SlackChannelClientTest {
    @Test
    fun `reads channels and next cursor from successful Slack response`() =
        withResponse(
            """{"ok":true,"channels":[{"id":"C1","name":"general"}],""" +
                """"response_metadata":{"next_cursor":"next"}}"""
        ) { client ->
            val page = client.fetchSlackChannelPage(null, 10, false)

            assertEquals(listOf("C1"), page.channels.map { it.id })
            assertEquals("next", page.nextCursor)
        }

    @Test
    fun `maps Slack rate limit response to retryable failure`() =
        withResponse("""{"ok":false,"error":"ratelimited"}""") { client ->
            val error =
                assertFailsWith<SlackChannelRateLimited> {
                    client.fetchSlackChannelPage(null, 10, false)
                }

            assertEquals(12, error.retryAfterSeconds)
        }

    @Test
    fun `preserves Slack API error message`() =
        withResponse("""{"ok":false,"error":"missing_scope"}""") { client ->
            val error =
                assertFailsWith<ToolException.ValidationFailure> {
                    client.fetchSlackChannelPage(null, 10, false)
                }

            assertEquals("missing_scope", error.message)
        }

    private fun withResponse(body: String, test: (MethodsClient) -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/conversations.list") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Retry-After", "12")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val config =
                SlackConfig().apply {
                    methodsEndpointUrlPrefix = "http://127.0.0.1:${http.address.port}/"
                }
            test(Slack.getInstance(config).methods("test-token"))
        } finally {
            http.stop(0)
        }
    }
}
