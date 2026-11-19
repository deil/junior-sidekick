package com.github.uncomplexco.sidekick.application.tools.files

import com.github.uncomplexco.sidekick.adapters.files.WorkspaceFilesystemConfiguration
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachmentCollector
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class ReplyAttachmentFilesystemSelectionTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest(name = "attaches project file with bash enabled={0}, provider={1}")
    @CsvSource("true,bwrap", "true,http", "false,http-v2")
    fun `local attachment download stages the project file for delivery`(
        enabled: Boolean,
        provider: String,
    ) = runBlocking {
        val paths = paths()
        Files.writeString(
            Files.createDirectories(paths.projectRoot).resolve("report.txt"),
            "report",
        )
        application(enabled, provider).use { context ->
            val filesystem = context.getBean(WorkspaceFilesystemFactory::class.java).create(paths)
            val collector = ReplyAttachmentCollector("C123", filesystem, paths.sessionRoot)
            val tool = ReplyAttachmentTools(collector)

            val result = tool.attachFile("/data/project/report.txt", "report.bin")

            assertEquals("report.bin", result.filename)
            assertEquals("text/plain", result.mimeType)
            assertEquals(6L, result.bytes)
            assertEquals("report", Files.readString(collector.collected().single().path))
        }
    }

    @Test
    fun `v2 attachments fail rather than reading a matching local project file`() = runBlocking {
        val paths = paths()
        val source =
            Files.writeString(
                Files.createDirectories(paths.projectRoot).resolve("report.txt"),
                "report",
            )
        application(true, "http-v2").use { context ->
            val filesystem = context.getBean(WorkspaceFilesystemFactory::class.java).create(paths)
            val collector = ReplyAttachmentCollector("C123", filesystem, paths.sessionRoot)
            val tool = ReplyAttachmentTools(collector)

            assertFailsWith<NotImplementedError> { tool.attachFile("/data/project/report.txt") }

            assertEquals(emptyList(), collector.collected())
            assertFalse(Files.exists(paths.sessionRoot.resolve("reply-attachments")))
            assertEquals("report", Files.readString(source))
        }
    }

    @Test
    fun `v2 workspace download throws without falling back to local copying`() = runBlocking {
        val paths = paths()
        Files.writeString(
            Files.createDirectories(paths.projectRoot).resolve("report.txt"),
            "report",
        )
        val destination = dir.resolve("conversation/report.txt")
        application(true, "http-v2").use { context ->
            val filesystem = context.getBean(WorkspaceFilesystemFactory::class.java).create(paths)

            assertFailsWith<NotImplementedError> {
                filesystem.transfers.download("C123", "/data/project/report.txt", destination)
            }

            assertFalse(Files.exists(destination))
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

    private fun paths(): VirtualPaths =
        VirtualPaths(
            sessionRoot = dir.resolve("session"),
            skillsRoot = dir.resolve("skills"),
            globalRoot = dir.resolve("global"),
            workRoot = dir.resolve("work"),
            projectRoot = dir.resolve("project"),
        )
}
