package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.ExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMount
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxMountMode
import java.nio.file.Files

private const val WRITABLE_ROOT_MODE = 0b010111111000

class WorkspaceSandboxExecutor(
    private val virtualPaths: VirtualPaths,
    private val scratchGid: Int? = null,
    private val delegate: SandboxExecutor,
) : SandboxExecutor {
    override fun execute(projectId: String, command: Command): ExecutionResult {
        virtualPaths.roots.forEach { root ->
            Files.createDirectories(root.real)
            if (root.writable) {
                scratchGid?.let { Files.setAttribute(root.real, "unix:gid", it) }
                Files.setAttribute(root.real, "unix:mode", WRITABLE_ROOT_MODE)
            }
        }

        val mounts =
            virtualPaths.roots.map { root ->
                SandboxMount(
                    source = root.real,
                    target = root.virtual,
                    mode = if (root.writable) SandboxMountMode.RW else SandboxMountMode.RO,
                )
            }
        return delegate.execute(projectId, command.copy(mounts = mounts))
    }
}
