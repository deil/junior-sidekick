package com.github.uncomplexco.sidekick.sandbox.service

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.pathString
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxServiceBwrapIntegrationTest {
    @Test
    fun `execute route uses sandbox-bwrap executor`() =
        testApplication {
            // Arrange
            val temp = Files.createTempDirectory("sandbox-service-bwrap")
            val fakeBwrap = fakeBwrap(temp)
            val rootfs = Files.createDirectory(temp.resolve("rootfs"))
            val allowed = Files.createDirectory(temp.resolve("allowed"))
            val config =
                SandboxServiceConfig(
                    token = "test-token",
                    bwrapPath = fakeBwrap.pathString,
                    rootfs = rootfs,
                    maxOutputBytes = 10_000,
                    uid = 123,
                    gid = 456,
                    allowedSourcePrefixes = listOf(allowed),
                )
            application { sandboxServiceModule(config) }

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
                            {"source": "${allowed.pathString}", "target": "/work", "mode": "rw"}
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            // Assert
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("--unshare-net"), body)
            assertTrue(body.contains("/work"), body)
            assertTrue(body.contains("pwd"), body)
        }

    private fun fakeBwrap(directory: java.nio.file.Path): java.nio.file.Path {
        val script = directory.resolve("fake-bwrap")
        Files.writeString(
            script,
            """
            #!/usr/bin/env bash
            printf '%s\n' "$@"
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)
        return script
    }
}
