package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.adapters.http.SandboxV2Client
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPage
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPageOptions
import com.github.uncomplexco.sidekick.ports.filesystem.FileMetadata
import com.github.uncomplexco.sidekick.ports.filesystem.GlobResult
import com.github.uncomplexco.sidekick.ports.filesystem.GrepResult
import com.github.uncomplexco.sidekick.ports.filesystem.PatchResult
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadOptions
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadResult
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFile
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import java.nio.file.Path

class RemoteWorkspaceFilesystem(private val client: SandboxV2Client) : WorkspaceFilesystem {
    override suspend fun seedProject(projectId: String, source: Path): Unit =
        TODO("Sandbox v2 project seeding is not implemented")

    override suspend fun upload(
        projectId: String,
        sessionId: String,
        source: Path,
    ): WorkspaceFile = TODO("Sandbox v2 file upload is not implemented")

    override suspend fun download(
        projectId: String,
        sessionId: String,
        source: String,
        destination: Path,
    ): FileMetadata = TODO("Sandbox v2 file download is not implemented")

    override suspend fun stat(projectId: String, path: String): FileMetadata? =
        client.stat(projectId, path)

    override suspend fun readText(
        projectId: String,
        path: String,
        options: TextReadOptions,
    ): TextReadResult = client.readText(projectId, path, options)

    override suspend fun writeText(projectId: String, path: String, content: String): Unit =
        client.writeText(projectId, path, content)

    override suspend fun editText(
        projectId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): Unit = client.editText(projectId, path, oldString, newString, replaceAll)

    override suspend fun applyPatch(
        projectId: String,
        path: String,
        patchText: String,
    ): PatchResult = client.applyPatch(projectId, path, patchText)

    override suspend fun listDirectory(
        projectId: String,
        path: String,
        options: DirectoryPageOptions,
    ): DirectoryPage = client.listDirectory(projectId, path, options)

    override suspend fun glob(projectId: String, path: String, pattern: String): GlobResult =
        client.glob(projectId, path, pattern)

    override suspend fun grep(
        projectId: String,
        path: String,
        pattern: String,
        include: String?,
    ): GrepResult = client.grep(projectId, path, pattern, include)
}
