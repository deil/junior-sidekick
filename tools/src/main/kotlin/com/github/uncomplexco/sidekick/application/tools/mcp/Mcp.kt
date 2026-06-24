package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.koog.ConnectedMcpServer
import com.github.uncomplexco.sidekick.application.turn.koog.McpServersRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.http.HttpClient as JavaHttpClient
import kotlin.time.Duration.Companion.seconds

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
        if (server.auth == "oauth") {
            val accessToken = oauth.accessToken(server) ?: error("MCP server ${server.id} requires OAuth")
            connect(server, accessToken)
        } else {
            connect(server, accessToken = null)
        }

    private suspend fun connect(
        server: McpServerConfig,
        accessToken: String?,
    ): ConnectedMcpServer =
        when (server.transport) {
            "stdio" -> stdioServer(server)
            "sse" -> httpServer(server, accessToken) { client -> SseClientTransport(client, server.url) }
            "streamable-http" -> httpServer(server, accessToken) { client -> client.mcpStreamableHttpTransport(server.url) }
            else -> error("Unsupported MCP transport for ${server.id}: ${server.transport}")
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
        accessToken: String?,
        transportFactory: (HttpClient) -> Transport,
    ): ConnectedMcpServer {
        val httpClient = mcpHttpClient(server, accessToken)
        val transport = transportFactory(httpClient)
        transport.onClose { httpClient.close() }
        try {
            return connectedServer(server, transport)
        } catch (error: Throwable) {
            if (server.transport == "sse") {
                logSseErrorResponse(server, accessToken)
            }
            httpClient.close()
            throw error
        }
    }

    private fun logSseErrorResponse(
        server: McpServerConfig,
        accessToken: String?,
    ) {
        runCatching {
            val requestBuilder =
                HttpRequest.newBuilder(URI.create(server.url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "text/event-stream")
                    .GET()
            accessToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

            val response = JavaHttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
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
                clientInfo = clientInfo(server),
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
        val sdkTools = client.listTools().tools
        return ToolRegistry {
            sdkTools.forEach { sdkTool ->
                runCatching {
                    val descriptor = prefixedDescriptor(server.id, DefaultMcpToolDescriptorParser.parse(sdkTool))
                    tool(
                        McpServerTool(
                            client = client,
                            originalToolName = sdkTool.name,
                            descriptor = descriptor,
                        ),
                    )
                }.onFailure {
                    log.warn("Ignoring invalid MCP tool {} from server {}", sdkTool.name, server.id, it)
                }
            }
        }
    }

    private fun mcpHttpClient(
        server: McpServerConfig,
        accessToken: String?,
    ): HttpClient =
        HttpClient {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = server.timeoutSeconds.seconds.inWholeMilliseconds
            }
            accessToken?.let { token ->
                defaultRequest {
                    header("Authorization", "Bearer $token")
                }
            }
        }

    private fun clientInfo(server: McpServerConfig): Implementation =
        Implementation(
            name = "sidekick-${server.id}",
            version = "1.0.0",
        )

    private fun prefixedDescriptor(
        serverId: String,
        descriptor: ToolDescriptor,
    ): ToolDescriptor = descriptor.copy(name = "${serverId}__${descriptor.name}")

    private companion object {
        private const val MAX_ERROR_RESPONSE_LOG_LENGTH = 4000
        private val log = LoggerFactory.getLogger(DefaultMcpServersRegistry::class.java)
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
