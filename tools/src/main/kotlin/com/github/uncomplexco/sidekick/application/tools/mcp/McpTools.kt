package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKoogJSONElement
import ai.koog.serialization.kotlinx.toKotlinxJsonElement
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.WORK_ROOT
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

internal const val MAX_MCP_TOOL_OUTPUT_CHARACTERS = 50_000

class McpStatusTools(
    private val ctx: TurnContext,
    private val servers: List<McpServerConfig>,
) {
    fun asTools(): List<ToolBase<*, *>> = servers.map { server -> McpStatusTool(ctx, server.id) }
}

@Component
class McpAuthTools(
    private val config: McpToolsConfig,
    private val oauth: McpOAuthService,
) {
    fun asTools(chat: ChatPlatformAdapter): List<ToolBase<*, *>> = config.servers.map { server -> ConnectMcpTool(server, oauth, chat) }
}

class McpServerTool(
    private val client: Client,
    private val originalToolName: String,
    descriptor: ToolDescriptor,
    private val workRoot: Path,
) : Tool<JSONObject, CallToolResult?>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<CallToolResult?>(),
        descriptor = descriptor,
    ) {
    private val json = Json.Default
    private val resultSerializer = CallToolResult.serializer().nullable

    override suspend fun execute(args: JSONObject): CallToolResult =
        client.callTool(
            name = originalToolName,
            arguments = prepareMcpToolArguments(originalToolName, args, json),
        )

    override fun decodeResult(
        rawResult: JSONElement,
        serializer: JSONSerializer,
    ): CallToolResult? = json.decodeFromJsonElement(resultSerializer, rawResult.toKotlinxJsonElement())

    override fun encodeResult(
        result: CallToolResult?,
        serializer: JSONSerializer,
    ): JSONElement = json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()

    override fun encodeResultToString(
        result: CallToolResult?,
        serializer: JSONSerializer,
    ): String = externalizeOutput(super.encodeResultToString(result, serializer))

    private fun externalizeOutput(output: String): String {
        if (output.length <= MAX_MCP_TOOL_OUTPUT_CHARACTERS) return output

        val tmpRoot = Files.createDirectories(workRoot.resolve("tmp"))
        val resultPath = tmpRoot.resolve("tool_${System.currentTimeMillis()}_result.txt")
        Files.writeString(resultPath, output)
        val virtualPath = "$WORK_ROOT/tmp/${resultPath.fileName}"

        return "(Output too large. Saved to $virtualPath. Use ${WorkspaceFileTools.TOOL_READ} with path to continue.)"
    }
}

private class McpStatusTool(
    private val ctx: TurnContext,
    private val serverId: String,
) : Tool<JSONObject, JSONObject>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<JSONObject>(),
        descriptor =
            ToolDescriptor(
                name = "get_mcp_status_$serverId",
                description = "Check whether the requester is already connected to $serverId MCP server",
            ),
    ) {
    override suspend fun execute(args: JSONObject): JSONObject {
        val connected = ctx.conversation.mcpServers.any { it.id == serverId }
        return JSONObject(
            mapOf(
                "server_id" to JSONPrimitive(serverId),
                "connected" to JSONPrimitive(connected),
            ),
        )
    }
}

private class ConnectMcpTool(
    private val server: McpServerConfig,
    private val oauth: McpOAuthService,
    private val chat: ChatPlatformAdapter,
) : Tool<JSONObject, JSONObject>(
        argsType = typeToken<JSONObject>(),
        resultType = typeToken<JSONObject>(),
        descriptor =
            ToolDescriptor(
                name = "connect_mcp_${server.id}",
                description = "Start connection flow for ${server.id} MCP server",
            ),
    ) {
    override suspend fun execute(args: JSONObject): JSONObject {
        val result = oauth.connect(server, chat)
        return JSONObject(
            mapOf(
                "server_id" to JSONPrimitive(result.serverId),
                "auth" to JSONPrimitive(result.auth),
                "started" to JSONPrimitive(result.started),
                "message" to JSONPrimitive(result.message),
            ),
        )
    }
}
