package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.DefaultMcpToolDescriptorParser
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.turn.koog.ConnectedMcpServer
import com.github.uncomplexco.sidekick.application.turn.koog.McpServersRegistry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
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
import kotlin.time.Duration.Companion.seconds

@Component
@ConfigurationProperties(prefix = "agent.mcp")
class McpToolsConfig {
    var servers: List<McpServerConfig> = emptyList()
}

data class McpServerConfig(
    var id: String = "",
    var transport: String = "sse",
    var url: String = "",
    var command: String = "",
    var args: List<String> = emptyList(),
    var env: Map<String, String> = emptyMap(),
    var timeoutSeconds: Long = 30,
)

@Component
class DefaultMcpServersRegistry(
    private val config: McpToolsConfig,
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
        when (server.transport) {
            "stdio" -> stdioServer(server)
            "sse" -> httpServer(server) { client -> SseClientTransport(client, server.url) }
            "streamable-http" -> httpServer(server) { client -> client.mcpStreamableHttpTransport(server.url) }
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
        transportFactory: (HttpClient) -> Transport,
    ): ConnectedMcpServer {
        val httpClient = mcpHttpClient(server)
        val transport = transportFactory(httpClient)
        transport.onClose { httpClient.close() }
        return connectedServer(server, transport)
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

    private fun mcpHttpClient(server: McpServerConfig): HttpClient =
        HttpClient {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = server.timeoutSeconds.seconds.inWholeMilliseconds
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
