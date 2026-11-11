package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.adapters.http.SandboxV2Client
import com.github.uncomplexco.sidekick.ports.sandbox.Command
import com.github.uncomplexco.sidekick.ports.sandbox.ExecutionResult
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor

class RemoteSandboxV2Executor(private val client: SandboxV2Client) : SandboxExecutor {
    override fun execute(projectId: String, command: Command): ExecutionResult =
        client.execute(projectId, command)
}
