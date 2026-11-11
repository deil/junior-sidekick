package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class WorkspaceFilesystemConfigurationTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest
    @ValueSource(strings = ["http", "bwrap"])
    fun `legacy providers read local workspace files`(provider: String) = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "local")

        application(enabled = true, provider = provider).use { context ->
            val filesystem = context.getBean(WorkspaceFilesystemFactory::class.java).create(paths())

            val result = filesystem.readText("C123", "/data/project/file.txt")

            assertEquals("local", result.content)
        }
    }

    @Test
    fun `disabled bash keeps filesystem operations local even with v2 configured`() = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "local")

        application(enabled = false, provider = "http-v2").use { context ->
            val filesystem = context.getBean(WorkspaceFilesystemFactory::class.java).create(paths())

            val result = filesystem.readText("C123", "/data/project/file.txt")

            assertEquals("local", result.content)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["http-v2", " HTTP-V2 "])
    fun `enabled v2 does not fall back to writing local files`(provider: String) = runBlocking {
        Files.writeString(dir.resolve("file.txt"), "original")

        application(enabled = true, provider = provider).use { context ->
            val filesystem = context.getBean(WorkspaceFilesystemFactory::class.java).create(paths())

            assertFailsWith<NotImplementedError> {
                filesystem.writeText("C123", "/data/project/file.txt", "changed")
            }
            assertEquals("original", Files.readString(dir.resolve("file.txt")))
        }
    }

    private fun application(
        enabled: Boolean,
        provider: String,
    ): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.registerBean(
            BashToolConfig::class.java,
            Supplier { BashToolConfig().apply { this.enabled = enabled } },
        )
        context.registerBean(
            SandboxExecutorConfig::class.java,
            Supplier { SandboxExecutorConfig().apply { this.provider = provider } },
        )
        context.register(WorkspaceFilesystemConfiguration::class.java)
        context.refresh()

        return context
    }

    private fun paths() =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = dir,
        )
}
