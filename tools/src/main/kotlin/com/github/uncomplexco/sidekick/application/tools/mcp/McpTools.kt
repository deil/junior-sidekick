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
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class McpStatusTools(
    private val ctx: TurnContext,
    private val servers: List<McpServerConfig>,
) {
    fun asTools(): List<ToolBase<*, *>> = servers.map { server -> McpStatusTool(ctx, server.id) }
}

class McpServerTool(
    private val client: Client,
    private val originalToolName: String,
    descriptor: ToolDescriptor,
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
            arguments = args.toKotlinxJsonObject(),
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
    ): String {
        if (result?.isError == true) {
            val errorText = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (errorText.isNotBlank()) return "Error: $errorText"

            val fallbackJson = json.encodeToJsonElement(resultSerializer, result).toKoogJSONElement()
            return "Error: ${serializer.encodeJSONElementToString(fallbackJson)}"
        }

        val preparedResultJson: JsonElement =
            result
                ?.let {
                    JsonObject(
                        json
                            .encodeToJsonElement(resultSerializer, result)
                            .jsonObject
                            .filter { (key, _) -> key !in listOf("type", "_meta") },
                    )
                }
                ?: JsonNull

        return serializer.encodeJSONElementToString(preparedResultJson.toKoogJSONElement())
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
        val connected = ctx.mcpServers.any { it.id == serverId }
        return JSONObject(
            mapOf(
                "server_id" to JSONPrimitive(serverId),
                "connected" to JSONPrimitive(connected),
            ),
        )
    }
}
