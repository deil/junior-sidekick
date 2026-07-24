package com.github.uncomplexco.sidekick.application.tools.mcp

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolOutputTest {
    @TempDir
    lateinit var tempDir: Path

    private val serializer = KotlinxSerializer()

    @Test
    fun `returns output at the character limit unchanged`() {
        val tool = tool()
        val emptyResult = CallToolResult(content = listOf(TextContent("")))
        val emptyOutput = encoded(tool, emptyResult)
        val result =
            CallToolResult(
                content = listOf(TextContent("x".repeat(MAX_MCP_TOOL_OUTPUT_CHARACTERS - emptyOutput.length))),
            )
        val output = encoded(tool, result)

        val actual = tool.encodeResultToString(result, serializer)

        assertEquals(MAX_MCP_TOOL_OUTPUT_CHARACTERS, output.length)
        assertEquals(output, actual)
        assertFalse(Files.exists(tempDir.resolve("tmp")))
    }

    @Test
    fun `saves oversized output as-is and returns only the file hint`() {
        val tool = tool()
        val result =
            CallToolResult(
                content = listOf(TextContent("original output\n" + "x".repeat(MAX_MCP_TOOL_OUTPUT_CHARACTERS))),
            )
        val output = encoded(tool, result)

        val actual = tool.encodeResultToString(result, serializer)

        val resultFiles = Files.list(tempDir.resolve("tmp")).use { it.toList() }
        val resultFile = resultFiles.single()
        assertTrue(resultFile.name.matches(Regex("tool_\\d+_result\\.txt")))
        assertEquals(output, Files.readString(resultFile))
        assertEquals(
            "(Output too large. Saved to /work/tmp/${resultFile.name}. " +
                "Use ${WorkspaceFileTools.TOOL_READ} with path to continue.)",
            actual,
        )
        assertFalse(actual.contains("original output"))
    }

    private fun tool(): McpServerTool =
        McpServerTool(
            client = Client(Implementation(name = "test", version = "1")),
            originalToolName = "test",
            descriptor = ToolDescriptor(name = "mcp__test__test", description = "Test"),
            workRoot = tempDir,
        )

    private fun encoded(
        tool: McpServerTool,
        result: CallToolResult,
    ): String = serializer.encodeJSONElementToString(tool.encodeResult(result, serializer))
}
