package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.adapters.sandbox.SameHostSandboxExecutor
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class SandboxExecutorFactoryTest {
    @Test
    fun `create rejects unsupported provider`() {
        // Arrange
        val config =
            SandboxExecutorConfig().apply {
                provider = "unsupported"
            }

        // Act / Assert
        assertFailsWith<IllegalStateException> { SandboxExecutorFactory(config).create() }
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
        assertIs<SameHostSandboxExecutor>(executor)
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
