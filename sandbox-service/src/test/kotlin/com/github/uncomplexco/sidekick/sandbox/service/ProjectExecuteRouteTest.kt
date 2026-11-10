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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProjectExecuteRouteTest {
    @TempDir lateinit var temp: Path

    @ParameterizedTest(name = "v2 execute rejects authorization {0}")
    @ValueSource(strings = ["", "Bearer wrong"])
    fun `rejects unauthenticated execution`(authorization: String) = testApplication {
        // Arrange
        val executor = RecordingProjectCommandExecutor()
        application { sandboxServiceModule(config(), projectExecutor = executor) }

        // Act
        val response =
            client.post("/api/v2/execute") { header(HttpHeaders.Authorization, authorization) }

        // Assert
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun `executes in project home when workdir is omitted`() = testApplication {
        // Arrange
        val executor = RecordingProjectCommandExecutor()
        application { sandboxServiceModule(config(), projectExecutor = executor) }

        // Act
        val response =
            client.post("/api/v2/execute") {
                header(HttpHeaders.Authorization, "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody("""{"projectId":"C123","command":"pwd","timeoutSeconds":5}""")
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            ProjectExecuteRequest("C123", "pwd", timeoutSeconds = 5),
            executor.requests.single(),
        )
        assertEquals(
            executor.response,
            Json.decodeFromString<ExecuteResponse>(response.bodyAsText()),
        )
    }

    @Test
    fun `passes explicit workdir to project execution`() = testApplication {
        // Arrange
        val executor = RecordingProjectCommandExecutor()
        application { sandboxServiceModule(config(), projectExecutor = executor) }

        // Act
        val response =
            client.post("/api/v2/execute") {
                header(HttpHeaders.Authorization, "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"projectId":"C456","command":"npm test","workdir":"repo","timeoutSeconds":120}"""
                )
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            ProjectExecuteRequest("C456", "npm test", "repo", 120),
            executor.requests.single(),
        )
    }

    @ParameterizedTest(name = "v2 execute rejects invalid request {0}")
    @ValueSource(
        strings =
            [
                """{"projectId":"../root","command":"pwd","timeoutSeconds":5}""",
                """{"projectId":"C123","command":" ","timeoutSeconds":5}""",
                """{"projectId":"C123","command":"pwd","timeoutSeconds":0}""",
                """{"projectId":"C123","command":"pwd","timeoutSeconds":2147484}""",
                """{"projectId":"C123","command":"pwd","workdir":" ","timeoutSeconds":5}""",
                """{"projectId":"C123","command":"pwd\u0000","timeoutSeconds":5}""",
            ]
    )
    fun `rejects invalid requests before invoking helper`(body: String) = testApplication {
        // Arrange
        val executor = RecordingProjectCommandExecutor()
        application { sandboxServiceModule(config(), projectExecutor = executor) }

        // Act
        val response =
            client.post("/api/v2/execute") {
                header(HttpHeaders.Authorization, "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(executor.requests.isEmpty())
    }

    @Test
    fun `v2 rejects legacy mount and network controls`() = testApplication {
        // Arrange
        val executor = RecordingProjectCommandExecutor()
        application { sandboxServiceModule(config(), projectExecutor = executor) }

        // Act
        val response =
            client.post("/api/v2/execute") {
                header(HttpHeaders.Authorization, "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"projectId":"C123","command":"pwd","timeoutSeconds":5,"networkEnabled":false,"mounts":[]}"""
                )
            }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(executor.requests.isEmpty())
    }

    private fun config() =
        SandboxServiceConfig(
            token = "test-token",
            rootfs = temp,
            allowedSourcePrefixes = emptyList(),
        )
}
