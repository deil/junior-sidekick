package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.koog.ConnectedMcpServer
import com.github.uncomplexco.sidekick.application.turn.koog.McpServersRegistry
import com.github.uncomplexco.sidekick.application.utils.Loggers
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.client.*
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.net.http.HttpClient as JavaHttpClient

@Component
@ConfigurationProperties(prefix = "agent.mcp")
class McpToolsConfig {
    var servers: List<McpServerConfig> = emptyList()
    var oauth: McpOauthConfig = McpOauthConfig()
}

data class McpOauthConfig(
    var publicBaseUrl: String = "",
)

data class McpServerConfig(
    var id: String = "",
    var transport: String = "sse",
    var url: String = "",
    var command: String = "",
    var args: List<String> = emptyList(),
    var env: Map<String, String> = emptyMap(),
    var timeoutSeconds: Long = 30,
    var auth: String = "",
    var authHeader: McpAuthHeaderConfig = McpAuthHeaderConfig(),
)

data class McpAuthHeaderConfig(
    var value: String = "",
)

@Component
class DefaultMcpServersRegistry(
    private val config: McpToolsConfig,
    private val oauth: McpOAuthService,
) : McpServersRegistry {
    override suspend fun connect(
        conversationId: ConversationId,
        userId: String,
    ): List<ConnectedMcpServer> =
        config.servers.mapNotNull { server ->
            runCatching { connect(server) }
                .onFailure { log.warn("Failed to connect MCP server {}", server.id, it) }
                .getOrNull()
        }

    private suspend fun connect(server: McpServerConfig): ConnectedMcpServer =
        when (server.auth) {
            "oauth" -> {
                val accessToken = oauth.accessToken(server) ?: error("MCP server ${server.id} requires OAuth")
                connect(server, authHeaderValue = "Bearer $accessToken")
            }

            "header" -> {
                val authHeaderValue = server.authHeader.value.takeIf { it.isNotBlank() }
                    ?: error("MCP server ${server.id} requires auth-header.value")
                connect(server, authHeaderValue)
            }

            else -> {
                connect(server, authHeaderValue = null)
            }
        }

    private suspend fun connect(
        server: McpServerConfig,
        authHeaderValue: String?,
    ): ConnectedMcpServer =
        when (server.transport) {
            "stdio" -> {
                stdioServer(server)
            }

            "sse" -> {
                httpServer(server, authHeaderValue) { client -> SseClientTransport(client, server.url) }
            }

            "streamable-http" -> {
                httpServer(
                    server,
                    authHeaderValue,
                ) { client -> client.mcpStreamableHttpTransport(server.url) }
            }

            else -> {
                error("Unsupported MCP transport for ${server.id}: ${server.transport}")
            }
        }

    private suspend fun stdioServer(server: McpServerConfig): ConnectedMcpServer {
        val processBuilder = ProcessBuilder(listOf(server.command) + server.args)
        processBuilder.environment().putAll(server.env)
        val process = processBuilder.start()
        val transport =
            StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )
        return connectedServer(server, transport, process)
    }

    private suspend fun httpServer(
        server: McpServerConfig,
        authHeaderValue: String?,
        transportFactory: (HttpClient) -> Transport,
    ): ConnectedMcpServer {
        val httpClient = mcpHttpClient(server, authHeaderValue)
        val transport = transportFactory(httpClient)
        transport.onClose { httpClient.close() }
        try {
            return connectedServer(server, transport)
        } catch (error: Throwable) {
            if (server.transport == "sse") {
                logSseErrorResponse(server, authHeaderValue)
            }
            httpClient.close()
            throw error
        }
    }

    private fun logSseErrorResponse(
        server: McpServerConfig,
        authHeaderValue: String?,
    ) {
        runCatching {
            val requestBuilder =
                HttpRequest
                    .newBuilder(URI.create(server.url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "text/event-stream")
                    .GET()
            authHeaderValue?.let { requestBuilder.header("Authorization", it) }

            val response =
                JavaHttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.warn(
                    "MCP SSE error response server={} url={} status={} body={}",
                    server.id,
                    server.url,
                    response.statusCode(),
                    response.body().take(MAX_ERROR_RESPONSE_LOG_LENGTH),
                )
            }
        }.onFailure {
            log.warn("Failed to read MCP SSE error response server={} url={}", server.id, server.url, it)
        }
    }

    private suspend fun connectedServer(
        server: McpServerConfig,
        transport: Transport,
        process: Process? = null,
    ): ConnectedMcpServer {
        val client =
            Client(
                clientInfo =
                    Implementation(
                        name = "sidekick",
                        version = "1.0.0",
                    ),
                options = ClientOptions().apply { timeout = server.timeoutSeconds.seconds },
            ).apply { connect(transport) }
        val registry = toolRegistry(server, client)
        return DefaultConnectedMcpServer(
            id = server.id,
            toolRegistry = registry,
            transport = transport,
            process = process,
        )
    }

    private suspend fun toolRegistry(
        server: McpServerConfig,
        client: Client,
    ): ToolRegistry {
        val tools = client.listTools().tools
        return ToolRegistry {
            tools.forEach { tool ->
                runCatching {
                    val descriptor = prepareMcpToolDescriptor(tool.name, DefaultMcpToolDescriptorParser.parse(tool))
                    tool(
                        McpServerTool(
                            client = client,
                            originalToolName = tool.name,
                            descriptor = descriptor.copy(name = "mcp__${server.id}__${descriptor.name}"),
                        ),
                    )
                }.onFailure {
                    log.warn("Ignoring invalid MCP tool {} from server {}", tool.name, server.id, it)
                }
            }
        }
    }

    private fun mcpHttpClient(
        server: McpServerConfig,
        authHeaderValue: String?,
    ): HttpClient =
        HttpClient {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = server.timeoutSeconds.seconds.inWholeMilliseconds
            }
            authHeaderValue?.let { headerValue ->
                defaultRequest {
                    header("Authorization", headerValue)
                }
            }
        }

    companion object {
        private const val MAX_ERROR_RESPONSE_LOG_LENGTH = 4000
        private val log = Loggers.MCP
    }
}

private class DefaultConnectedMcpServer(
    override val id: String,
    override val toolRegistry: ToolRegistry,
    private val transport: Transport,
    private val process: Process? = null,
) : ConnectedMcpServer {
    override suspend fun close() {
        transport.close()
        process?.destroy()
    }
}
