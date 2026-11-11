package com.github.uncomplexco.sidekick.adapters.git

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.git.WorkspaceGit

class WorkspaceGitFactory(private val createGit: (VirtualPaths) -> WorkspaceGit) {
    fun create(virtualPaths: VirtualPaths): WorkspaceGit = createGit(virtualPaths)
}
