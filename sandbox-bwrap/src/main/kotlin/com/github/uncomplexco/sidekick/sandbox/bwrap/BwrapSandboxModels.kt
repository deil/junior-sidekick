package com.github.uncomplexco.sidekick.sandbox.bwrap

import java.nio.file.Path

data class BwrapSandboxConfig(
    val bwrapPath: String = "bwrap",
    val rootfs: Path,
    val maxOutputBytes: Int,
    val uid: Int,
    val gid: Int,
)

data class BwrapSandboxRequest(
    val command: String,
    val workdir: String,
    val timeoutSeconds: Long,
    val networkEnabled: Boolean,
    val mounts: List<BwrapMount>,
)

data class BwrapMount(
    val source: Path,
    val target: String,
    val mode: BwrapMountMode,
)

enum class BwrapMountMode {
    RO,
    RW,
}

data class BwrapSandboxResult(
    val ok: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val output: String,
    val workdir: String,
)
