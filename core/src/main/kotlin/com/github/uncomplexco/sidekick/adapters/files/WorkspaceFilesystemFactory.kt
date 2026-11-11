package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import org.springframework.stereotype.Component

@Component
class WorkspaceFilesystemFactory {
    fun create(virtualPaths: VirtualPaths): WorkspaceFilesystem =
        LocalWorkspaceFilesystem(virtualPaths)
}
