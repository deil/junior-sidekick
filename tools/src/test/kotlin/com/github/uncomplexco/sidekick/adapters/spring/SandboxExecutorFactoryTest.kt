package com.github.uncomplexco.sidekick.adapters.spring

import kotlin.test.assertFailsWith
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
