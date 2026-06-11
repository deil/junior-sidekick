package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider as KoogMcpToolRegistryProvider
import ai.koog.agents.mcp.defaultStdioTransport
import ai.koog.agents.mcp.metadata.McpServerInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
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
class ConfiguredMcpToolRegistryProvider(
    private val config: McpToolsConfig,
) {
    private var registry: ToolRegistry? = null

    suspend fun build(): ToolRegistry {
        registry?.let { return it }

        val registries = config.servers.map { server -> registry(server) }
        val built = registries.fold(ToolRegistry { }) { acc, registry -> acc + registry }
        registry = built
        return built
    }

    private suspend fun registry(server: McpServerConfig): ToolRegistry =
        when (server.transport) {
            "stdio" -> stdioRegistry(server)
            "sse" ->
                clientRegistry(
                    server,
                    SseClientTransport(mcpHttpClient(server), server.url),
                    McpServerInfo(url = server.url),
                )
            "streamable-http" ->
                clientRegistry(
                    server,
                    mcpHttpClient(server).mcpStreamableHttpTransport(server.url),
                    McpServerInfo(url = server.url),
                )
            else -> error("Unsupported MCP transport for ${server.id}: ${server.transport}")
        }

    private suspend fun stdioRegistry(server: McpServerConfig): ToolRegistry {
        val processBuilder = ProcessBuilder(listOf(server.command) + server.args)
        processBuilder.environment().putAll(server.env)
        val process = processBuilder.start()
        return clientRegistry(
            server,
            KoogMcpToolRegistryProvider.defaultStdioTransport(process),
            McpServerInfo(command = listOf(server.command).plus(server.args).joinToString(" ")),
        )
    }

    private suspend fun clientRegistry(
        server: McpServerConfig,
        transport: Transport,
        serverInfo: McpServerInfo,
    ): ToolRegistry {
        val client = Client(
            clientInfo = clientInfo(server),
            options = ClientOptions().apply { timeout = server.timeoutSeconds.seconds },
        ).apply { connect(transport) }
        return KoogMcpToolRegistryProvider.fromClient(client, serverInfo)
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
}
