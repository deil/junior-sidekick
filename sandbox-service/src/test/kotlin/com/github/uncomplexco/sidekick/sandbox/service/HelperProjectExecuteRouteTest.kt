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
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HelperProjectExecuteRouteTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `v2 returns stub helper output without executing the requested command`() =
        testApplication {
            // Arrange
            val helper = StubProjectHelper(temp)
            application {
                sandboxServiceModule(
                    SandboxServiceConfig(
                        token = "test-token",
                        rootfs = temp,
                        allowedSourcePrefixes = emptyList(),
                    ),
                    projectExecutor = helper.executor(),
                )
            }

            // Act
            val response =
                client.post("/api/v2/execute") {
                    header(HttpHeaders.Authorization, "Bearer test-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"projectId":"C123","command":"stub:success","timeoutSeconds":5}""")
                }

            // Assert
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ExecuteResponse(true, 0, false, false, "stub output\n", "/home/stub"),
                Json.decodeFromString<ExecuteResponse>(response.bodyAsText()),
            )
            assertEquals(
                listOf(
                    "bash",
                    "--project",
                    "C123",
                    "--timeout-seconds",
                    "5",
                    "--max-output-bytes",
                    "1234",
                    "--json",
                    "--",
                    "stub:success",
                ),
                Json.decodeFromString<List<String>>(helper.invocations().last()),
            )
        }

    @Test
    fun `v2 preserves timeout and null exit status from helper`() = testApplication {
        // Arrange
        val helper = StubProjectHelper(temp)
        application {
            sandboxServiceModule(
                SandboxServiceConfig(
                    token = "test-token",
                    rootfs = temp,
                    allowedSourcePrefixes = emptyList(),
                ),
                projectExecutor = helper.executor(),
            )
        }

        // Act
        val response =
            client.post("/api/v2/execute") {
                header(HttpHeaders.Authorization, "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody("""{"projectId":"C123","command":"stub:timeout","timeoutSeconds":5}""")
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            ExecuteResponse(false, null, true, false, "stub timeout", "/home/stub"),
            Json.decodeFromString<ExecuteResponse>(response.bodyAsText()),
        )
    }
}
