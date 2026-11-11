package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.adapters.http.SandboxV2Client
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorConfig
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorFactory
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import com.github.uncomplexco.sidekick.application.tools.bash.BashTools
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class SameHostSandboxExecutorTest {
    @Test
    fun `v2 returns setup diagnostics from failed execution responses`() {
        // Arrange
        val server =
            testServer("/api/v2/execute") { exchange ->
                exchange.respond(
                    200,
                    """
                    {
                      "ok": false,
                      "exitCode": null,
                      "timedOut": false,
                      "outputTruncated": false,
                      "output": "ENOENT: no such file or directory, chdir '/data/project'",
                      "workdir": "/data/project"
                    }
                    """
                        .trimIndent(),
                )
            }
        server.use {
            val executor = RemoteSandboxV2Executor(SandboxV2Client(server.baseUrl(), "secret"))

            // Act
            val response =
                executor.execute("C123", Command("stub command", "/data/project", 5, false))

            // Assert
            assertEquals(false, response.ok)
            assertEquals(null, response.exitCode)
            assertEquals(
                "ENOENT: no such file or directory, chdir '/data/project'",
                response.output,
            )
        }
    }

    @Test
    fun `v2 uses the project ID supplied to each execution`() {
        // Arrange
        val captured = mutableListOf<CapturedRequest>()
        val server =
            testServer("/api/v2/execute") { exchange ->
                captured.add(exchange.capture())
                exchange.respond(
                    200,
                    """
                    {
                      "ok": true,
                      "exitCode": 0,
                      "timedOut": false,
                      "outputTruncated": false,
                      "output": "stub output",
                      "workdir": "/home/stub"
                    }
                    """
                        .trimIndent(),
                )
            }
        server.use {
            val executor = RemoteSandboxV2Executor(SandboxV2Client(server.baseUrl(), "secret"))
            val command = Command("stub command", null, 5, false)

            // Act
            executor.execute("C123", command)
            executor.execute("C456", command)

            // Assert
            assertEquals(
                listOf("C123", "C456"),
                captured.map {
                    Json.parseToJsonElement(it.body).jsonObject["projectId"]?.jsonPrimitive?.content
                },
            )
        }
    }

    @Test
    fun `http-v2 provider executes in project home without preparing local mounts`() {
        // Arrange
        val local = Files.createTempDirectory("project-http-sandbox-test")
        val paths =
            VirtualPaths(
                local.resolve("session"),
                local.resolve("skills"),
                local.resolve("global"),
                local.resolve("work"),
                local.resolve("project"),
            )
        lateinit var captured: CapturedRequest
        val server =
            testServer("/api/v2/execute") { exchange ->
                captured = exchange.capture()
                exchange.respond(
                    200,
                    """
                    {
                      "ok": true,
                      "exitCode": 0,
                      "timedOut": false,
                      "outputTruncated": false,
                      "output": "stub output",
                      "workdir": "/home/sku_project"
                    }
                    """
                        .trimIndent(),
                )
            }
        server.use {
            val config =
                SandboxExecutorConfig().apply {
                    provider = "http-v2"
                    http.baseUrl = server.baseUrl()
                    http.token = "secret"
                }
            val toolConfig =
                BashToolConfig().apply {
                    enabled = true
                    networkEnabled = true
                }
            val tools = BashTools(toolConfig, "C123", SandboxExecutorFactory(config).create(paths))

            // Act
            val result = tools.bash("stub command", "test request")

            // Assert
            assertEquals("stub output", result.output)
            assertEquals("/home/sku_project", result.workdir)
            assertEquals("/api/v2/execute", captured.path)
            assertEquals("Bearer secret", captured.authorization)
            assertEquals(
                Json.parseToJsonElement(
                    """{"projectId":"C123","command":"stub command","timeoutSeconds":120}"""
                ),
                Json.parseToJsonElement(captured.body),
            )
            assertFalse(Files.exists(paths.workRoot))
            assertFalse(Files.exists(paths.projectRoot))
            assertFalse(Files.exists(paths.sessionRoot))
        }
    }

    @Test
    fun `v2 preserves explicit dot workdir and timeout response`() {
        // Arrange
        lateinit var captured: CapturedRequest
        val server =
            testServer("/api/v2/execute") { exchange ->
                captured = exchange.capture()
                exchange.respond(
                    200,
                    """
                    {
                      "ok": false,
                      "exitCode": null,
                      "timedOut": true,
                      "outputTruncated": false,
                      "output": "stub timeout",
                      "workdir": "/"
                    }
                    """
                        .trimIndent(),
                )
            }
        server.use {
            val executor = RemoteSandboxV2Executor(SandboxV2Client(server.baseUrl(), "secret"))

            // Act
            val result =
                executor.execute("C456", Command("stub timeout", ".", 5, false, emptyList()))

            // Assert
            assertEquals(false, result.ok)
            assertEquals(true, result.timedOut)
            assertEquals(null, result.exitCode)
            assertEquals("stub timeout", result.output)
            assertEquals(
                Json.parseToJsonElement(
                    """{"projectId":"C456","command":"stub timeout","workdir":".","timeoutSeconds":5}"""
                ),
                Json.parseToJsonElement(captured.body),
            )
        }
    }

    @Test
    fun `legacy client omits unspecified workdir for service to default`() {
        // Arrange
        lateinit var captured: CapturedRequest
        val server = testServer { exchange ->
            captured = exchange.capture()
            exchange.respond(
                200,
                """
                {
                  "ok": true,
                  "exitCode": 0,
                  "timedOut": false,
                  "outputTruncated": false,
                  "output": "stub output",
                  "workdir": "/"
                }
                """
                    .trimIndent(),
            )
        }
        server.use {
            val executor = SameHostSandboxExecutor(server.baseUrl(), "secret")

            // Act
            val result = executor.execute("C123", Command("stub command", null, 5, false))

            // Assert
            assertEquals("/", result.workdir)
            assertEquals(
                Json.parseToJsonElement(
                    """{"command":"stub command","timeoutSeconds":5,"networkEnabled":false,"mounts":[]}"""
                ),
                Json.parseToJsonElement(captured.body),
            )
        }
    }

    @Test
    fun `execute posts sandbox command to service`() {
        // Arrange
        val scratch = Files.createTempDirectory("http-sandbox-executor-test")
        lateinit var captured: CapturedRequest
        val server = testServer { exchange ->
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
                """
                    .trimIndent(),
            )
        }
        server.use {
            val config =
                SandboxExecutorConfig().apply {
                    http.baseUrl = server.baseUrl()
                    http.token = "secret"
                }
            val executor = SandboxExecutorFactory(config).create()

            // Act
            val result =
                executor.execute(
                    "C123",
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
            assertFalse(body.containsKey("projectId"))
            assertEquals("pwd", body["command"]?.jsonPrimitive?.content)
            assertEquals("/tmp", body["workdir"]?.jsonPrimitive?.content)
            assertEquals("7", body["timeoutSeconds"]?.jsonPrimitive?.content)
            assertEquals(true, body["networkEnabled"]?.jsonPrimitive?.boolean)
            val mount = body["mounts"]!!.jsonArray.single().jsonObject
            assertEquals(
                scratch.toAbsolutePath().normalize().pathString,
                mount["source"]?.jsonPrimitive?.content,
            )
            assertEquals("/work", mount["target"]?.jsonPrimitive?.content)
            assertEquals("rw", mount["mode"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute fails when service returns non success status`() {
        // Arrange
        val server = testServer { exchange ->
            exchange.respond(401, "{\"error\":\"Unauthorized\"}")
        }
        server.use {
            val executor = SameHostSandboxExecutor(baseUrl = server.baseUrl(), token = "secret")

            // Act / Assert
            val error =
                assertFailsWith<IllegalStateException> {
                    executor.execute(
                        "C123",
                        Command(
                            command = "pwd",
                            workdir = "/",
                            timeoutSeconds = 7,
                            networkEnabled = false,
                            mounts = emptyList(),
                        ),
                    )
                }
            assertEquals(
                "Bash sandbox service returned HTTP 401: {\"error\":\"Unauthorized\"}",
                error.message,
            )
        }
    }

    private fun testServer(
        path: String = "/api/execute",
        handler: (HttpExchange) -> Unit,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange -> handler(exchange) }
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
