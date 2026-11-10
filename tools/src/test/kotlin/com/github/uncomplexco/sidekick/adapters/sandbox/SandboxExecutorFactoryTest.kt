package com.github.uncomplexco.sidekick.adapters.sandbox

import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class SandboxExecutorFactoryTest {
    @Test
    fun `http-v2 requires project context`() {
        // Arrange
        val config =
            SandboxExecutorConfig().apply {
                provider = "http-v2"
                http.baseUrl = "http://localhost:7171"
                http.token = "secret"
            }

        // Act / Assert
        assertFailsWith<IllegalArgumentException> { SandboxExecutorFactory(config).create() }
    }

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
        assertFailsWith<IllegalStateException> { SandboxExecutorFactory(config).create() }
    }
}
