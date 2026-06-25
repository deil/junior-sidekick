package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.tools.SystemTools.Companion.TOOL_REPORT_ASSISTANT_ACTIVITY
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.pathString

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
    private val workRoot: Path,
    private val sandboxExecutor: SandboxExecutor,
) : ToolSet {
    private val logger = LoggerFactory.getLogger(BashTools::class.java)

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

        logger.info(
            """
            Running bash command: $command
            Working directory: $workdir
            Timeout: $resolvedTimeout s
            Description: $description
            """.trimIndent(),
        )

        prepareWorkRoot()
        val result =
            try {
                sandboxExecutor.execute(
                    Command(
                        command = command,
                        workdir = workdir,
                        timeoutSeconds = resolvedTimeout,
                        networkEnabled = config.networkEnabled,
                        mounts =
                            listOf(
                                SandboxMount(
                                    source = workRoot,
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

    private fun prepareWorkRoot() {
        Files.createDirectories(workRoot)
        val gid = config.scratchGid
        logger.info("Preparing bash work root: path={} configuredScratchGid={} before={}", workRoot, gid, fileAttributes(workRoot))
        gid ?: return
        Files.setAttribute(workRoot, "unix:gid", gid)
        Files.setPosixFilePermissions(workRoot, workPermissions)
        Files.setAttribute(workRoot, "unix:mode", WORK_MODE)
        val actualGid = Files.getAttribute(workRoot, "unix:gid") as Int
        logger.info("Prepared bash work root: path={} configuredScratchGid={} after={}", workRoot, gid, fileAttributes(workRoot))
        check(actualGid == gid) {
            "Bash work directory ${workRoot.pathString} has gid $actualGid, expected configured scratch gid $gid"
        }
    }

    private companion object {
        private val workPermissions =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
            )
        private const val WORK_MODE = 0b010111111000
    }
}

private fun fileAttributes(path: Path): String =
    try {
        val uid = Files.getAttribute(path, "unix:uid")
        val gid = Files.getAttribute(path, "unix:gid")
        val mode = (Files.getAttribute(path, "unix:mode") as Int) and 0b111111111111
        "uid=$uid gid=$gid mode=${mode.toString(8)}"
    } catch (error: UnsupportedOperationException) {
        "unix-attributes-unavailable"
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
