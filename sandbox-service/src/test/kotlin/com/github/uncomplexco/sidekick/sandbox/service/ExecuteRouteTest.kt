package com.github.uncomplexco.sidekick.sandbox.service

import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxRequest
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxResult
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecuteRouteTest {
    @Test
    fun `execute rejects missing bearer token`() =
        testApplication {
            // Arrange
            application { sandboxServiceModule(testConfig()) }

            // Act
            val response = client.post("/api/execute")

            // Assert
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `execute rejects invalid bearer token`() =
        testApplication {
            // Arrange
            application { sandboxServiceModule(testConfig()) }

            // Act
            val response =
                client.post("/api/execute") {
                    header(HttpHeaders.Authorization, "Bearer wrong")
                }

            // Assert
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `execute maps valid request to sandbox executor`() =
        testApplication {
            // Arrange
            val allowed = Files.createTempDirectory("sandbox-service-allowed")
            val mountSource = Files.createDirectory(allowed.resolve("scratch"))
            lateinit var captured: BwrapSandboxRequest
            application {
                sandboxServiceModule(testConfig(allowedPrefixes = listOf(allowed))) { request ->
                    captured = request
                    BwrapSandboxResult(
                        ok = true,
                        exitCode = 0,
                        timedOut = false,
                        outputTruncated = false,
                        output = "ok",
                        workdir = request.workdir,
                    )
                }
            }

            // Act
            val response =
                client.post("/api/execute") {
                    header(HttpHeaders.Authorization, "Bearer test-token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "command": "pwd",
                          "workdir": "/tmp",
                          "timeoutSeconds": 5,
                          "networkEnabled": true,
                          "mounts": [
                            {"source": "${mountSource.pathString}", "target": "/work", "mode": "rw"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            // Assert
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("pwd", captured.command)
            assertEquals("/tmp", captured.workdir)
            assertEquals(5, captured.timeoutSeconds)
            assertEquals(true, captured.networkEnabled)
            assertEquals(mountSource.toRealPath(), captured.mounts.single().source)
            assertEquals("/work", captured.mounts.single().target)
            assertEquals("rw", captured.mounts.single().mode.name.lowercase())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("true", body["ok"]?.jsonPrimitive?.content)
            assertEquals("ok", body["output"]?.jsonPrimitive?.content)
        }

    @Test
    fun `execute rejects mount source outside allowed prefixes`() =
        testApplication {
            // Arrange
            val allowed = Files.createTempDirectory("sandbox-service-allowed")
            val disallowed = Files.createTempDirectory("sandbox-service-disallowed")
            application { sandboxServiceModule(testConfig(allowedPrefixes = listOf(allowed))) }

            // Act
            val response =
                client.post("/api/execute") {
                    header(HttpHeaders.Authorization, "Bearer test-token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "command": "pwd",
                          "workdir": "/",
                          "timeoutSeconds": 5,
                          "networkEnabled": false,
                          "mounts": [
                            {"source": "${disallowed.pathString}", "target": "/work", "mode": "rw"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            // Assert
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Mount source is not allowed"))
        }

    @Test
    fun `execute rejects invalid mount mode`() =
        testApplication {
            // Arrange
            val allowed = Files.createTempDirectory("sandbox-service-allowed")
            application { sandboxServiceModule(testConfig(allowedPrefixes = listOf(allowed))) }

            // Act
            val response =
                client.post("/api/execute") {
                    header(HttpHeaders.Authorization, "Bearer test-token")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "command": "pwd",
                          "workdir": "/",
                          "timeoutSeconds": 5,
                          "networkEnabled": false,
                          "mounts": [
                            {"source": "${allowed.pathString}", "target": "/work", "mode": "read-write"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            // Assert
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Mount mode must be ro or rw"))
        }

    private fun testConfig(allowedPrefixes: List<java.nio.file.Path> = emptyList()): SandboxServiceConfig {
        val rootfs = Files.createTempDirectory("sandbox-service-rootfs")
        return SandboxServiceConfig(
            token = "test-token",
            rootfs = rootfs,
            allowedSourcePrefixes = allowedPrefixes,
        )
    }
}
