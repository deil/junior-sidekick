package com.github.uncomplexco.sidekick.application.tools.bash

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.tools.SystemTools.Companion.TOOL_REPORT_ASSISTANT_ACTIVITY
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

@Component
@ConfigurationProperties(prefix = "agent.tools.bash")
class BashToolConfig {
    var enabled: Boolean = false
    var networkEnabled: Boolean = false
    var bwrapPath: String = "bwrap"
    var rootfs: String = ""
    var maxOutputBytes: Int = 50 * 1024
    var uid: Int = 65534
    var gid: Int = 65534
}

class BashTools(
    private val config: BashToolConfig,
    private val scratchRoot: Path,
    private val runner: SandboxedBashRunner = BwrapBashRunner(config),
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
        @LLMDescription("Timeout in seconds. Defaults to 120 and may not exceed 600")
        timeout: Long? = null,
    ): BashResult {
        if (!config.enabled) {
            throw ToolException.ValidationFailure("Bash tool is disabled.")
        }
        if (command.isBlank()) {
            throw ToolException.ValidationFailure("command is required")
        }

        val resolvedTimeout = timeout?.coerceAtMost(600) ?: 120
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
        return runner.run(command, scratchRoot, workdir, resolvedTimeout)
    }
}

interface SandboxedBashRunner {
    fun run(
        command: String,
        scratchRoot: Path,
        workdir: String,
        timeoutSeconds: Long,
    ): BashResult
}

class BwrapBashRunner(
    private val config: BashToolConfig,
) : SandboxedBashRunner {
    override fun run(
        command: String,
        scratchRoot: Path,
        workdir: String,
        timeoutSeconds: Long,
    ): BashResult {
        val rootfs = rootfsPath()
        val scratch = safeDirectory(scratchRoot, "scratch")
        val sandboxWorkdir = sandboxWorkdir(workdir)
        val args = bwrapArgs(rootfs, scratch, sandboxWorkdir, command)
        val process = ProcessBuilder(args).redirectErrorStream(true).start()

        val output = LimitedOutput(config.maxOutputBytes)
        val stdout = Thread { process.inputStream.use { output.copyFrom(it) } }.apply { start() }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        stdout.join(1_000)

        return BashResult(
            ok = finished && process.exitValue() == 0,
            exit_code = if (finished) process.exitValue() else null,
            timed_out = !finished,
            output_truncated = output.truncated,
            output = output.text(),
            workdir = sandboxWorkdir,
        )
    }

    internal fun bwrapArgs(
        rootfs: Path,
        scratch: Path,
        workdir: String,
        command: String,
    ): List<String> =
        buildList {
            add(config.bwrapPath)
            add("--die-with-parent")
            add("--new-session")
            add("--clearenv")
            add("--unshare-user")
            add("--unshare-ipc")
            add("--unshare-pid")
            add("--unshare-uts")
            add("--unshare-cgroup-try")
            if (!config.networkEnabled) {
                add("--unshare-net")
            }
            add("--uid")
            add(config.uid.toString())
            add("--gid")
            add(config.gid.toString())
            add("--ro-bind")
            add(rootfs.pathString)
            add("/")
            add("--bind")
            add(scratch.pathString)
            add("/work")
            add("--tmpfs")
            add("/tmp")
            add("--proc")
            add("/proc")
            add("--dev")
            add("/dev")
            add("--chdir")
            add(workdir)
            add("--setenv")
            add("HOME")
            add("/work")
            add("--setenv")
            add("PWD")
            add(workdir)
            add("--setenv")
            add("SHELL")
            add("/bin/bash")
            add("--setenv")
            add("USER")
            add("sidekick")
            add("--setenv")
            add("LOGNAME")
            add("sidekick")
            add("--setenv")
            add("TMPDIR")
            add("/tmp")
            add("--setenv")
            add("MISE_CACHE_DIR")
            add("/work/.mise/cache")
            add("--setenv")
            add("MISE_CONFIG_DIR")
            add("/work/.mise/config")
            add("--setenv")
            add("MISE_DATA_DIR")
            add("/work/.mise/data")
            add("--setenv")
            add("MISE_STATE_DIR")
            add("/work/.mise/state")
            add("--setenv")
            add("PATH")
            add("/usr/local/bin:/usr/bin:/bin")
            add("/bin/bash")
            add("-lc")
            add(command)
        }

    private fun sandboxWorkdir(workdir: String): String {
        val requested = workdir.trim().ifEmpty { "/" }
        val absolute = if (requested.startsWith("/")) requested else "/$requested"
        val normalized = Path.of(absolute).normalize().pathString
        if (!normalized.startsWith("/")) {
            throw ToolException.ValidationFailure("workdir must resolve to an absolute sandbox path")
        }
        return normalized
    }

    private fun rootfsPath(): Path {
        if (config.rootfs.isBlank()) {
            throw ToolException.ValidationFailure("Bash sandbox rootfs is not configured.")
        }
        return safeDirectory(Path.of(config.rootfs), "rootfs")
    }

    private fun safeDirectory(
        path: Path,
        label: String,
    ): Path {
        val normalized = path.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(normalized)) {
            throw ToolException.ValidationFailure("Bash sandbox $label must not be a symbolic link: $normalized")
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw ToolException.ValidationFailure("Bash sandbox $label is not a directory: $normalized")
        }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }
}

private class LimitedOutput(
    private val maxBytes: Int,
) {
    private val bytes = ByteArrayOutputStream(maxBytes.coerceAtLeast(0))
    var truncated: Boolean = false
        private set

    fun copyFrom(input: java.io.InputStream) {
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                return
            }
            val remaining = maxBytes - bytes.size()
            if (remaining <= 0) {
                truncated = true
                continue
            }
            bytes.write(buffer, 0, minOf(read, remaining))
            if (read > remaining) {
                truncated = true
            }
        }
    }

    fun text(): String = bytes.toString(StandardCharsets.UTF_8)
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
