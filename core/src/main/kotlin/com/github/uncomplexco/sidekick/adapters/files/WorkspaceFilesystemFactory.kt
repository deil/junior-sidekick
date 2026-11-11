package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem

class WorkspaceFilesystemFactory(
    private val createFilesystem: (VirtualPaths) -> WorkspaceFilesystem = ::LocalWorkspaceFilesystem
) {
    fun create(virtualPaths: VirtualPaths): WorkspaceFilesystem = createFilesystem(virtualPaths)
}
