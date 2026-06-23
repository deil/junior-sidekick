package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpSandboxExecutorTest {
    @Test
    fun `execute posts sandbox command to service`() {
        // Arrange
        val scratch = Files.createTempDirectory("http-sandbox-executor-test")
        lateinit var captured: CapturedRequest
        val server =
            testServer { exchange ->
                captured = exchange.capture()
                exchange.respond(
                    200,
                    """
                    {
                      "ok": true,
                      "exitCode": 0,
                      "timedOut": false,
                      "outputTruncated": false,
                      "output": "ok",
                      "workdir": "/tmp"
                    }
                    """.trimIndent(),
                )
            }
        server.use {
            val executor = HttpSandboxExecutor(baseUrl = server.baseUrl(), token = "secret")

            // Act
            val result =
                executor.execute(
                    Command(
                        command = "pwd",
                        workdir = "/tmp",
                        timeoutSeconds = 7,
                        networkEnabled = true,
                        mounts = listOf(SandboxMount(scratch, "/work", SandboxMountMode.RW)),
                    ),
                )

            // Assert
            assertEquals(true, result.ok)
            assertEquals(0, result.exitCode)
            assertEquals("ok", result.output)
            assertEquals("/tmp", result.workdir)
            assertEquals("/api/execute", captured.path)
            assertEquals("Bearer secret", captured.authorization)

            val body = Json.parseToJsonElement(captured.body).jsonObject
            assertEquals("pwd", body["command"]?.jsonPrimitive?.content)
            assertEquals("/tmp", body["workdir"]?.jsonPrimitive?.content)
            assertEquals("7", body["timeoutSeconds"]?.jsonPrimitive?.content)
            assertEquals(true, body["networkEnabled"]?.jsonPrimitive?.boolean)
            val mount = body["mounts"]!!.jsonArray.single().jsonObject
            assertEquals(scratch.toAbsolutePath().normalize().pathString, mount["source"]?.jsonPrimitive?.content)
            assertEquals("/work", mount["target"]?.jsonPrimitive?.content)
            assertEquals("rw", mount["mode"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute fails when service returns non success status`() {
        // Arrange
        val server = testServer { exchange -> exchange.respond(401, "{\"error\":\"Unauthorized\"}") }
        server.use {
            val executor = HttpSandboxExecutor(baseUrl = server.baseUrl(), token = "secret")

            // Act / Assert
            val error =
                assertFailsWith<IllegalStateException> {
                    executor.execute(
                        Command(
                            command = "pwd",
                            workdir = "/",
                            timeoutSeconds = 7,
                            networkEnabled = false,
                            mounts = emptyList(),
                        ),
                    )
                }
            assertEquals("Bash sandbox service returned HTTP 401: {\"error\":\"Unauthorized\"}", error.message)
        }
    }

    private fun testServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/execute") { exchange -> handler(exchange) }
        server.start()
        return server
    }

    private fun HttpServer.baseUrl(): String = "http://127.0.0.1:${address.port}"

    private fun HttpServer.use(block: (HttpServer) -> Unit) {
        try {
            block(this)
        } finally {
            stop(0)
        }
    }

    private fun HttpExchange.capture(): CapturedRequest =
        CapturedRequest(
            path = requestURI.path,
            authorization = requestHeaders.getFirst("Authorization"),
            body = requestBody.bufferedReader().use { it.readText() },
        )

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

private data class CapturedRequest(
    val path: String,
    val authorization: String?,
    val body: String,
)
