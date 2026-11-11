package com.github.uncomplexco.sidekick.application.tools.mcp

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.json.Json

internal class McpOAuthStateStore(private val agentConfig: AgentConfig, private val json: Json) {
    fun loadClient(serverId: String): OAuthClientState? {
        val path = mcpDir(serverId).resolve("oauth-client.json")
        if (!path.exists()) return null
        return json.decodeFromString<OAuthClientState>(Files.readString(path))
    }

    fun saveClient(serverId: String, client: OAuthClientState) =
        Files.writeString(
            mcpDir(serverId).resolve("oauth-client.json"),
            json.encodeToString(client),
        )

    fun loadToken(serverId: String): OAuthTokenState? {
        val path = mcpDir(serverId).resolve("oauth-token.json")
        if (!path.exists()) return null
        return json.decodeFromString<OAuthTokenState>(Files.readString(path))
    }

    fun saveToken(serverId: String, token: OAuthTokenState) =
        Files.writeString(mcpDir(serverId).resolve("oauth-token.json"), json.encodeToString(token))

    private fun mcpDir(serverId: String): Path {
        val dir = agentConfig.stateDirectoryPath().resolve("mcp").resolve(serverId)
        Files.createDirectories(dir)
        return dir
    }
}
