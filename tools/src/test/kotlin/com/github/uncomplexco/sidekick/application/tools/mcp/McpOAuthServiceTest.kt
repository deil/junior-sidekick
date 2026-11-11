package com.github.uncomplexco.sidekick.application.tools.mcp

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class McpOAuthServiceTest {
    @TempDir lateinit var dir: Path

    private val server = McpServerConfig(id = "test", auth = "oauth")

    @Test
    fun `returns null when no token is stored`() {
        val service = service()

        val token = service.accessToken(server)

        assertNull(token)
    }

    @Test
    fun `returns unexpired token without loading a client`() {
        storeToken(OAuthTokenState("existing", expiresAt = "9999-01-01T00:00:00Z"))
        val service = service()

        val token = service.accessToken(server)

        assertEquals("existing", token)
    }

    @Test
    fun `returns null for expired token without refresh token`() {
        storeToken(OAuthTokenState("expired", expiresAt = "2000-01-01T00:00:00Z"))
        val service = service()

        val token = service.accessToken(server)

        assertNull(token)
    }

    @Test
    fun `returns null for expired token without stored client`() {
        storeToken(OAuthTokenState("expired", "refresh", expiresAt = "2000-01-01T00:00:00Z"))
        val service = service()

        val token = service.accessToken(server)

        assertNull(token)
    }

    @Test
    fun `refresh stores new access token and retains omitted refresh token`() =
        withPendingTokenEndpoint(200, """{"access_token":"refreshed","expires_in":3600}""") {
            storeToken(OAuthTokenState("expired", "refresh", expiresAt = "2000-01-01T00:00:00Z"))
            val service = service()

            val token = service.accessToken(server)

            assertEquals("refreshed", token)
            val stored =
                Json.decodeFromString<OAuthTokenState>(
                    Files.readString(dir.resolve("mcp/test/oauth-token.json"))
                )
            assertEquals("refreshed", stored.accessToken)
            assertEquals("refresh", stored.refreshToken)
        }

    @Test
    fun `failed refresh returns null and leaves stored token unchanged`() =
        withPendingTokenEndpoint(400, """{"error":"invalid_grant"}""") {
            val original = OAuthTokenState("expired", "refresh", expiresAt = "2000-01-01T00:00:00Z")
            storeToken(original)
            val service = service()

            val token = service.accessToken(server)

            assertNull(token)
            assertEquals(
                original,
                Json.decodeFromString<OAuthTokenState>(
                    Files.readString(dir.resolve("mcp/test/oauth-token.json"))
                ),
            )
        }

    @Test
    fun `callback stores exchanged token and clears pending flow`() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/token") { exchange ->
            val body = """{"access_token":"exchanged","expires_in":3600}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val client =
                OAuthClientState(
                    serverId = "test",
                    redirectUrl = "https://example.com/callback",
                    clientId = "client",
                    pending =
                        PendingOAuthFlow(
                            "state",
                            "verifier",
                            "https://example.com/authorize",
                            "http://127.0.0.1:${http.address.port}/token",
                            "2000-01-01T00:00:00Z",
                        ),
                )
            val path = Files.createDirectories(dir.resolve("mcp/test"))
            Files.writeString(path.resolve("oauth-client.json"), Json.encodeToString(client))
            val service = service()

            val result = service.completeCallback("state", "code")

            assertEquals("test", result)
            val stored =
                Json.decodeFromString<OAuthTokenState>(
                    Files.readString(path.resolve("oauth-token.json"))
                )
            assertEquals("exchanged", stored.accessToken)
            val updated =
                Json.decodeFromString<OAuthClientState>(
                    Files.readString(path.resolve("oauth-client.json"))
                )
            assertEquals(client.copy(pending = null), updated)
        } finally {
            http.stop(0)
        }
    }

    private fun withPendingTokenEndpoint(status: Int, body: String, test: () -> Unit) {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/token") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val client =
                OAuthClientState(
                    serverId = "test",
                    redirectUrl = "https://example.com/callback",
                    clientId = "client",
                    pending =
                        PendingOAuthFlow(
                            "state",
                            "verifier",
                            "https://example.com/authorize",
                            "http://127.0.0.1:${http.address.port}/token",
                            "2000-01-01T00:00:00Z",
                        ),
                )
            val path = Files.createDirectories(dir.resolve("mcp/test"))
            Files.writeString(path.resolve("oauth-client.json"), Json.encodeToString(client))
            test()
        } finally {
            http.stop(0)
        }
    }

    private fun service(): McpOAuthService =
        McpOAuthService(
            AgentConfig("Sidekick", dir.toString(), dir.resolve("workspace").toString()),
            McpToolsConfig().apply { servers = listOf(server) },
        )

    private fun storeToken(token: OAuthTokenState) {
        val path = Files.createDirectories(dir.resolve("mcp/test"))
        Files.writeString(path.resolve("oauth-token.json"), Json.encodeToString(token))
    }
}
