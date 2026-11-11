package com.github.uncomplexco.sidekick.ports.git

interface WorkspaceGit {
    suspend fun clone(projectId: String, url: String, path: String): GitCloneResult

    suspend fun push(
        projectId: String,
        path: String,
        refspec: String? = null,
        all: Boolean = false,
        tags: Boolean = false,
    ): GitPushResult

    suspend fun pull(
        projectId: String,
        path: String,
        remote: String,
        refspec: String,
    ): GitPullResult
}
