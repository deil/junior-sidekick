package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider as KoogMcpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

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
            "sse" -> KoogMcpToolRegistryProvider.fromSseUrl(server.url, clientInfo(server))
            "streamable-http" ->
                KoogMcpToolRegistryProvider.streamableHttp {
                    url = server.url
                    name = clientInfo(server).name
                    version = clientInfo(server).version
                }
            else -> error("Unsupported MCP transport for ${server.id}: ${server.transport}")
        }

    private suspend fun stdioRegistry(server: McpServerConfig): ToolRegistry {
        val processBuilder = ProcessBuilder(listOf(server.command) + server.args)
        processBuilder.environment().putAll(server.env)
        return KoogMcpToolRegistryProvider.fromProcess(processBuilder.start(), clientInfo(server))
    }

    private fun clientInfo(server: McpServerConfig): Implementation =
        Implementation(
            name = "sidekick-${server.id}",
            version = "1.0.0",
        )
}
