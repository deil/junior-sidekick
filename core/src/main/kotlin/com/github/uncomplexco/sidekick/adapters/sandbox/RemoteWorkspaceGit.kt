package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.ports.git.GitCloneResult
import com.github.uncomplexco.sidekick.ports.git.GitPullResult
import com.github.uncomplexco.sidekick.ports.git.GitPushResult
import com.github.uncomplexco.sidekick.ports.git.WorkspaceGit

class RemoteWorkspaceGit : WorkspaceGit {
    override suspend fun clone(projectId: String, url: String, path: String): GitCloneResult =
        TODO("Sandbox v2 Git clone is not implemented")

    override suspend fun push(
        projectId: String,
        path: String,
        refspec: String?,
        all: Boolean,
        tags: Boolean,
    ): GitPushResult = TODO("Sandbox v2 Git push is not implemented")

    override suspend fun pull(
        projectId: String,
        path: String,
        remote: String,
        refspec: String,
    ): GitPullResult = TODO("Sandbox v2 Git pull is not implemented")
}
