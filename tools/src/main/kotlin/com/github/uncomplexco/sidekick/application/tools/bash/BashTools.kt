package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.validate
import com.github.uncomplexco.sidekick.application.tools.files.WorkspaceFileTools
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "agent.tools.bash")
class BashToolConfig {
    var enabled: Boolean = false
    var networkEnabled: Boolean = false
    var timeout: Long = 120
}

class BashTools(
    private val config: BashToolConfig,
    private val projectId: String,
    private val sandboxExecutor: SandboxExecutor,
) : ToolSet {
    private val logger = LoggerFactory.getLogger(Loggers.TOOLS.name + ".bash")

    @Tool
    @LLMDescription(
        "Run a shell command in the configured execution environment. For local workspace files, " +
            "use ${WorkspaceFileTools.TOOL_GLOB}, ${WorkspaceFileTools.TOOL_GREP} " +
            "or ${WorkspaceFileTools.TOOL_READ}. " +
            "Project execution has a separate remote filesystem."
    )
    fun bash(
        @LLMDescription("Shell command string to execute") command: String,
        @LLMDescription("Concise description of the command's purpose") description: String,
        @LLMDescription("Working directory. Omit to use the execution environment's default")
        workdir: String? = null,
        @LLMDescription(
            "Timeout in seconds. Defaults to the configured timeout and may not exceed it"
        )
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
            """
                .trimIndent()
        )

        val result =
            try {
                sandboxExecutor.execute(
                    projectId,
                    Command(
                        command = command,
                        workdir = workdir,
                        timeoutSeconds = resolvedTimeout,
                        networkEnabled = config.networkEnabled,
                    ),
                )
            } catch (error: IllegalArgumentException) {
                throw ToolException.ValidationFailure(
                    error.message ?: "Invalid bash sandbox request"
                )
            }

        return BashResult(
            ok = result.ok,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            outputTruncated = result.outputTruncated,
            output = result.output,
            workdir = result.workdir,
        )
    }
}

@Serializable
data class BashResult(
    val ok: Boolean,
    @SerialName("exit_code") val exitCode: Int?,
    @SerialName("timed_out") val timedOut: Boolean,
    @SerialName("output_truncated") val outputTruncated: Boolean,
    val output: String,
    val workdir: String,
)
