package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.tools.SystemTools.Companion.TOOL_REPORT_ASSISTANT_ACTIVITY
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxCommand
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
@ConfigurationProperties(prefix = "agent.tools.bash")
class BashToolConfig {
    var enabled: Boolean = false
    var networkEnabled: Boolean = false
    var timeout: Long = 120
}

class BashTools(
    private val config: BashToolConfig,
    private val scratchRoot: Path,
    private val sandboxExecutor: SandboxExecutor,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Run a bash command in a sandbox. Only home directory /work is writable. Before proceeding, always notify the user via `$TOOL_REPORT_ASSISTANT_ACTIVITY` tool.",
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
        if (command.isBlank()) {
            throw ToolException.ValidationFailure("command is required")
        }

        val resolvedTimeout = timeout?.coerceAtMost(config.timeout) ?: config.timeout
        if (resolvedTimeout < 1) {
            throw ToolException.ValidationFailure("timeoutSeconds must be at least 1")
        }

        LoggerFactory.getLogger(BashTools::class.java).info(
            """
            Running bash command: $command
            Working directory: $workdir
            Timeout: $resolvedTimeout s
            Description: $description
            """.trimIndent(),
        )

        Files.createDirectories(scratchRoot)
        val result =
            try {
                sandboxExecutor.execute(
                    SandboxCommand(
                        command = command,
                        workdir = workdir,
                        timeoutSeconds = resolvedTimeout,
                        networkEnabled = config.networkEnabled,
                        mounts =
                            listOf(
                                SandboxMount(
                                    source = scratchRoot,
                                    target = "/work",
                                    mode = SandboxMountMode.RW,
                                ),
                            ),
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
