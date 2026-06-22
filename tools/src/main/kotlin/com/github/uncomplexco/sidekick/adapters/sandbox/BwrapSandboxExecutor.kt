package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.ports.sandbox.SandboxCommand
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapMount
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapMountMode
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandbox
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxRequest

class BwrapSandboxExecutor(
    private val bwrap: BwrapSandbox,
) : SandboxExecutor {
    override fun execute(command: SandboxCommand): SandboxExecutionResult {
        val result =
            bwrap.execute(
                BwrapSandboxRequest(
                    command = command.command,
                    workdir = command.workdir,
                    timeoutSeconds = command.timeoutSeconds,
                    networkEnabled = command.networkEnabled,
                    mounts = command.mounts.map { it.toBwrapMount() },
                ),
            )
        return SandboxExecutionResult(
            ok = result.ok,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
            outputTruncated = result.outputTruncated,
            output = result.output,
            workdir = result.workdir,
        )
    }
}

private fun SandboxMount.toBwrapMount(): BwrapMount =
    BwrapMount(
        source = source,
        target = target,
        mode = mode.toBwrapMode(),
    )

private fun SandboxMountMode.toBwrapMode(): BwrapMountMode =
    when (this) {
        SandboxMountMode.RO -> BwrapMountMode.RO
        SandboxMountMode.RW -> BwrapMountMode.RW
    }
