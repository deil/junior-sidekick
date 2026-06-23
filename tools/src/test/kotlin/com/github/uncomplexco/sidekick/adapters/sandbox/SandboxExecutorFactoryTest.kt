package com.github.uncomplexco.sidekick.adapters.sandbox

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SandboxExecutorFactoryTest {
    @Test
    fun `create returns http sandbox executor for http provider`() {
        // Arrange
        val config =
            SandboxExecutorConfig().apply {
                provider = "http"
                http.baseUrl = "http://localhost:7171"
                http.token = "secret"
            }

        // Act
        val executor = SandboxExecutorFactory(config).create()

        // Assert
        assertIs<HttpSandboxExecutor>(executor)
    }

    @Test
    fun `create rejects http provider without token`() {
        // Arrange
        val config =
            SandboxExecutorConfig().apply {
                provider = "http"
                http.baseUrl = "http://localhost:7171"
            }

        // Act / Assert
        assertFailsWith<IllegalStateException> {
            SandboxExecutorFactory(config).create()
        }
    }
}
