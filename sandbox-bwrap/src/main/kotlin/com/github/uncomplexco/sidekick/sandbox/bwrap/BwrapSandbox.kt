package com.github.uncomplexco.sidekick.sandbox.bwrap

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

class BwrapSandbox(
    private val config: BwrapSandboxConfig,
) {
    fun execute(request: BwrapSandboxRequest): BwrapSandboxResult {
        require(request.command.isNotBlank()) { "command is required" }
        require(request.timeoutSeconds >= 1) { "timeoutSeconds must be at least 1" }

        val rootfs = safeDirectory(config.rootfs, "rootfs")
        val workdir = sandboxPath(request.workdir, "workdir")
        val mounts = request.mounts.map(::validatedMount)
        val args = bwrapArgs(rootfs, workdir, request.networkEnabled, mounts, request.command)
        val process = ProcessBuilder(args).redirectErrorStream(true).start()

        val output = LimitedOutput(config.maxOutputBytes)
        val stdout = Thread { process.inputStream.use { output.copyFrom(it) } }.apply { start() }

        val finished = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        stdout.join(1_000)

        return BwrapSandboxResult(
            ok = finished && process.exitValue() == 0,
            exitCode = if (finished) process.exitValue() else null,
            timedOut = !finished,
            outputTruncated = output.truncated,
            output = output.text(),
            workdir = workdir,
        )
    }

    internal fun bwrapArgs(
        rootfs: Path,
        workdir: String,
        networkEnabled: Boolean,
        mounts: List<BwrapMount>,
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
            if (!networkEnabled) {
                add("--unshare-net")
            }
            add("--uid")
            add(config.uid.toString())
            add("--gid")
            add(config.gid.toString())
            add("--ro-bind")
            add(rootfs.pathString)
            add("/")
            for (mount in mounts) {
                add(if (mount.mode == BwrapMountMode.RO) "--ro-bind" else "--bind")
                add(mount.source.pathString)
                add(mount.target)
            }
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

    private fun validatedMount(mount: BwrapMount): BwrapMount =
        BwrapMount(
            source = existingPath(mount.source, "mount source"),
            target = sandboxPath(mount.target, "mount target"),
            mode = mount.mode,
        )

    private fun sandboxPath(
        path: String,
        label: String,
    ): String {
        val requested = path.trim().ifEmpty { "/" }
        val absolute = if (requested.startsWith("/")) requested else "/$requested"
        val normalized = Path.of(absolute).normalize().pathString
        require(normalized.startsWith("/")) { "$label must resolve to an absolute sandbox path" }
        return normalized
    }

    private fun safeDirectory(
        path: Path,
        label: String,
    ): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(normalized)) { "Bash sandbox $label must not be a symbolic link: $normalized" }
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) { "Bash sandbox $label is not a directory: $normalized" }
        return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }

    private fun existingPath(
        path: Path,
        label: String,
    ): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) { "Bash sandbox $label does not exist: $normalized" }
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
