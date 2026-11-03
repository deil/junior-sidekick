package com.github.uncomplexco.sidekick.application.tools.mcp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class McpHttpClientTest {
    @Test
    fun `compressed HTTP response is decoded without content length mismatch`() = runBlocking {
        // Arrange
        val body =
            """{"jsonrpc":"2.0","id":1,"result":{"message":"${"MCP response ".repeat(20)}"}}"""
        val compressed =
            ByteArrayOutputStream()
                .apply {
                    GZIPOutputStream(this).use { it.write(body.toByteArray()) }
                }
                .toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        server.start()

        try {
            mcpHttpClient(McpServerConfig(), null).use { client ->
                // Act
                val response =
                    client.post("http://127.0.0.1:${server.address.port}/mcp").bodyAsText()

                // Assert
                assertEquals(body, response)
            }
        } finally {
            server.stop(0)
        }
    }
}
