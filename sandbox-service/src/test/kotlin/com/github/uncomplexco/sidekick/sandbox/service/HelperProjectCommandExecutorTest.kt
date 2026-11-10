package com.github.uncomplexco.sidekick.sandbox.service

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `helper failure is not reported as a command result`() {
        // Arrange
        val helper = StubProjectHelper(temp)
        helper.failEnsure()
        val executor = helper.executor()

        // Act / Assert
        assertFailsWith<IllegalStateException> {
            executor.execute(ProjectExecuteRequest("C123", "stub:success", timeoutSeconds = 5))
        }
    }
}
