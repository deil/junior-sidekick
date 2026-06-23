package com.github.uncomplexco.sidekick.ports.sandbox

import java.nio.file.Path

fun interface SandboxExecutor {
    fun execute(command: Command): ExecutionResult
}

data class Command(
    val command: String,
    val workdir: String,
    val timeoutSeconds: Long,
    val networkEnabled: Boolean,
    val mounts: List<SandboxMount>,
)

data class SandboxMount(
    val source: Path,
    val target: String,
    val mode: SandboxMountMode,
)

enum class SandboxMountMode {
    RO,
    RW,
}

data class ExecutionResult(
    val ok: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val outputTruncated: Boolean,
    val output: String,
    val workdir: String,
)
