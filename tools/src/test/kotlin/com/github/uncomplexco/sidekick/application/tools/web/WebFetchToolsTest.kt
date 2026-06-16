package com.github.uncomplexco.sidekick.application.tools.web

import ai.koog.agents.core.tools.ToolException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebFetchToolsTest {
    @Test
    fun `normalizes format timeout and http url`() {
        assertEquals("markdown", normalizeWebFetchFormat(null))
        assertEquals("text", normalizeWebFetchFormat(" TEXT "))
        assertEquals(30, normalizeWebFetchTimeout(null))
        assertEquals(120, normalizeWebFetchTimeout(500))
        assertEquals("https", normalizeHttpUri("https://example.com").scheme)

        assertThrows<ToolException.ValidationFailure> { normalizeWebFetchFormat("json") }
        assertThrows<ToolException.ValidationFailure> { normalizeWebFetchTimeout(0) }
        assertThrows<ToolException.ValidationFailure> { normalizeHttpUri("file:///etc/passwd") }
    }

    @Test
    fun `fetches text from http url`() =
        withServer { server ->
            server.handler("/plain") { exchange ->
                exchange.respond("hello", "text/plain")
            }

            val result = webFetchTools().webFetch("${server.url}/plain", format = "text", timeout = 5)

            assertTrue(result.ok)
            assertEquals("text/plain", result.contentType)
            assertEquals("text", result.format)
            assertEquals("hello", result.output)
        }

    @Test
    fun `follows redirects`() =
        withServer { server ->
            server.handler("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/target")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.handler("/target") { exchange ->
                exchange.respond("redirected", "text/plain")
            }

            assertEquals("redirected", webFetchTools().webFetch("${server.url}/redirect", format = "text").output)
        }

    @Test
    fun `rejects images and oversized responses`() =
        withServer { server ->
            server.handler("/image") { exchange ->
                exchange.respond("png", "image/png")
            }
            server.handler("/big") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/plain")
                exchange.responseHeaders.add("Content-Length", (MAX_WEB_FETCH_RESPONSE_BYTES + 1).toString())
                exchange.sendResponseHeaders(200, 0)
                exchange.close()
            }

            assertThrows<ToolException.ValidationFailure> {
                webFetchTools().webFetch("${server.url}/image", format = "html")
            }
            assertThrows<ToolException.ValidationFailure> {
                webFetchTools().webFetch("${server.url}/big", format = "text")
            }
        }

    @Test
    fun `retries cloudflare challenge with agent user agent`() =
        withServer { server ->
            val userAgents = mutableListOf<String>()
            server.handler("/challenge") { exchange ->
                userAgents += exchange.requestHeaders.getFirst("User-Agent")
                if (userAgents.size == 1) {
                    exchange.responseHeaders.add("cf-mitigated", "challenge")
                    exchange.respond("challenge", "text/plain", status = 403)
                } else {
                    exchange.respond("ok", "text/plain")
                }
            }

            assertEquals("ok", webFetchTools().webFetch("${server.url}/challenge", format = "text").output)
            assertContains(userAgents.first(), "Mozilla/5.0")
            assertEquals("Sidekick", userAgents.last())
        }

    private fun webFetchTools(): WebFetchTools = WebFetchTools("Sidekick")

    private fun withServer(test: (TestWebServer) -> Unit) {
        val server = TestWebServer()
        try {
            test(server)
        } finally {
            server.stop()
        }
    }
}

private class TestWebServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String = "http://127.0.0.1:${server.address.port}"

    init {
        server.start()
    }

    fun handler(
        path: String,
        handler: (HttpExchange) -> Unit,
    ) {
        server.createContext(path) { exchange -> handler(exchange) }
    }

    fun stop() {
        server.stop(0)
    }
}

private fun HttpExchange.respond(
    body: String,
    contentType: String,
    status: Int = 200,
) {
    val bytes = body.toByteArray()
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
