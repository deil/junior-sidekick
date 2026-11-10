package com.github.uncomplexco.sidekick.sandbox.service

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HelperProjectCommandExecutorTest {
    @TempDir lateinit var temp: Path

    @Test
    fun `ensures user before executing and returns helper output`() {
        // Arrange
        val helper = StubProjectHelper(temp)
        val executor = helper.executor()

        // Act
        val response = executor.execute(ProjectExecuteRequest("C123", "stub:failure", "repo", 5))

        // Assert
        assertEquals(
            ExecuteResponse(false, 7, false, true, "stub failure", "/home/stub/repo"),
            response,
        )
        assertEquals(
            listOf(
                listOf("user:ensure", "--project", "C123"),
                listOf(
                    "bash",
                    "--project",
                    "C123",
                    "--workdir",
                    "repo",
                    "--timeout-seconds",
                    "5",
                    "--max-output-bytes",
                    "1234",
                    "--json",
                    "--",
                    "stub:failure",
                ),
            ),
            helper.invocations().map { Json.decodeFromString<List<String>>(it) },
        )
    }

    @Test
    fun `helper failure returns failed execution with diagnostic output`() {
        // Arrange
        val helper = StubProjectHelper(temp)
        helper.failEnsure()
        val executor = helper.executor()

        // Act
        val response =
            executor.execute(ProjectExecuteRequest("C123", "stub:success", timeoutSeconds = 5))

        // Assert
        assertEquals(
            ExecuteResponse(
                false,
                null,
                false,
                false,
                "Sandbox helper failed during user:ensure (exit 1): stub provisioning failure",
                "",
            ),
            response,
        )
    }

    @Test
    fun `helper diagnostic output respects the response byte cap`() {
        // Arrange
        val helper = StubProjectHelper(temp)
        helper.failEnsure()
        val executor =
            HelperProjectCommandExecutor(helper.helper.toString(), helper.sudo.toString(), 7)

        // Act
        val response =
            executor.execute(ProjectExecuteRequest("C123", "stub:success", timeoutSeconds = 5))

        // Assert
        assertEquals(ExecuteResponse(false, null, false, true, "Sandbox", ""), response)
    }

    @Test
    fun `helper startup failure returns failed execution with diagnostic output`() {
        // Arrange
        val executor =
            HelperProjectCommandExecutor(
                "/unused/helper",
                temp.resolve("missing-sudo").toString(),
                1234,
            )

        // Act
        val response =
            executor.execute(ProjectExecuteRequest("C123", "stub:success", timeoutSeconds = 5))

        // Assert
        assertFalse(response.ok)
        assertNull(response.exitCode)
        assertTrue(response.output.startsWith("Cannot start sandbox helper during user:ensure:"))
    }
}
