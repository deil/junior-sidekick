package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.tools.system.SystemTools.Companion.TOOL_REPORT_ASSISTANT_ACTIVITY
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

@Component
@ConfigurationProperties(prefix = "agent.tools.bash")
class BashToolConfig {
    var enabled: Boolean = false
    var networkEnabled: Boolean = false
    var timeout: Long = 120
    var scratchGid: Int? = null
}

class BashTools(
    private val config: BashToolConfig,
    private val virtualPaths: VirtualPaths,
    private val sandboxExecutor: SandboxExecutor,
) : ToolSet {
    private val logger = LoggerFactory.getLogger(Loggers.TOOLS.name + ".bash")

    @Tool
    @LLMDescription(
        "Run a shell command. Only home directory /work is writable. Do not use to read or search for files, use ${WorkspaceFileTools.TOOL_GLOB}, ${WorkspaceFileTools.TOOL_GREP} or ${WorkspaceFileTools.TOOL_READ} instead. Before proceeding, always notify the user via `$TOOL_REPORT_ASSISTANT_ACTIVITY` tool",
    )
    fun bash(
        @LLMDescription("Shell command string to execute")
        command: String,
        @LLMDescription("Concise description of the command's purpose")
        description: String,
        @LLMDescription("Working directory inside the sandbox. Defaults to /")
        workdir: String = "/",
        @LLMDescription("Timeout in seconds. Defaults to the configured timeout and may not exceed it")
        timeout: Long? = null,
    ): BashResult {
        if (!config.enabled) {
            throw ToolException.ValidationFailure("Bash tool is disabled.")
        }
        validate(command.isNotBlank()) { "command is required" }

        val resolvedTimeout = timeout?.coerceAtMost(config.timeout) ?: config.timeout
        validate(resolvedTimeout >= 1) { "timeoutSeconds must be at least 1" }

        logger.info(
            """
            Running bash command: $command
            Working directory: $workdir
            Timeout: $resolvedTimeout s
            Description: $description
            """.trimIndent(),
        )

        prepareVirtualRoots()

        val result =
            try {
                sandboxExecutor.execute(
                    Command(
                        command = command,
                        workdir = workdir,
                        timeoutSeconds = resolvedTimeout,
                        networkEnabled = config.networkEnabled,
                        mounts =
                            virtualPaths.roots.map { root ->
                                SandboxMount(
                                    source = root.real,
                                    target = root.virtual,
                                    mode = if (root.writable) SandboxMountMode.RW else SandboxMountMode.RO,
                                )
                            },
                    ),
                )
            } catch (error: IllegalArgumentException) {
                throw ToolException.ValidationFailure(error.message ?: "Invalid bash sandbox request")
            }

        return BashResult(
            ok = result.ok,
            exit_code = result.exitCode,
            timed_out = result.timedOut,
            output_truncated = result.outputTruncated,
            output = result.output,
            workdir = result.workdir,
        )
    }

    private fun prepareVirtualRoots() {
        virtualPaths.roots.filter { !it.writable }.forEach { Files.createDirectories(it.real) }

        virtualPaths.roots.filter { it.writable }.forEach { root ->
            Files.createDirectories(root.real)

            config.scratchGid?.also { gid ->
                Files.setAttribute(root.real, "unix:gid", gid)
            }

            Files.setPosixFilePermissions(root.real, writableRootPermissions)
            Files.setAttribute(root.real, "unix:mode", WRITABLE_ROOT_MODE)
        }
    }

    private companion object {
        private val writableRootPermissions =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
            )
        private const val WRITABLE_ROOT_MODE = 0b010111111000
    }
}

@Serializable
data class BashResult(
    val ok: Boolean,
    val exit_code: Int?,
    val timed_out: Boolean,
    val output_truncated: Boolean,
    val output: String,
    val workdir: String,
)
