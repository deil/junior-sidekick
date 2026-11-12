package com.github.uncomplexco.sidekick.ports.filesystem

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths

fun interface WorkspaceFilesystemFactory {
    fun create(virtualPaths: VirtualPaths): WorkspaceFilesystem
}
