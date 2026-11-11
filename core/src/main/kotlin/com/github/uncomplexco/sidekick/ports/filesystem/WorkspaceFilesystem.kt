package com.github.uncomplexco.sidekick.ports.filesystem

interface WorkspaceFilesystem {
    suspend fun stat(projectId: String, path: String): FileMetadata?

    suspend fun readText(
        projectId: String,
        path: String,
        options: TextReadOptions = TextReadOptions(),
    ): TextReadResult

    suspend fun writeText(projectId: String, path: String, content: String)

    suspend fun applyPatch(projectId: String, path: String, patchText: String): PatchResult

    suspend fun editText(
        projectId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean = false,
    )

    suspend fun listDirectory(
        projectId: String,
        path: String,
        options: DirectoryPageOptions = DirectoryPageOptions(),
    ): DirectoryPage

    suspend fun glob(projectId: String, path: String, pattern: String): GlobResult

    suspend fun grep(
        projectId: String,
        path: String,
        pattern: String,
        include: String? = null,
    ): GrepResult
}
