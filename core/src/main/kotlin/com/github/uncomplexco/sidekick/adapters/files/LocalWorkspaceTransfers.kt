package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.SESSION_ROOT
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.WORK_ROOT
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import com.github.uncomplexco.sidekick.ports.filesystem.FileMetadata
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class LocalWorkspaceTransfers(private val resolve: (String) -> Path) {
    fun upload(source: Path): WorkspaceFile {
        val path = "$SESSION_ROOT/${source.fileName}"
        val metadata = copy(source, resolve(path))
        return WorkspaceFile(path, metadata)
    }

    fun download(source: String, destination: Path): FileMetadata =
        copy(resolve(sessionPath(source)), destination)

    private fun sessionPath(source: String): String {
        val directory = source.substringBefore('/')
        val root =
            when (directory) {
                "attachments" -> SESSION_ROOT
                "work" -> WORK_ROOT
                else ->
                    throw IllegalArgumentException(
                        "Session file must be under attachments/ or work/: $source"
                    )
            }
        return root + source.removePrefix(directory)
    }

    private fun copy(source: Path, destination: Path): FileMetadata {
        require(Files.isRegularFile(source, NOFOLLOW_LINKS)) {
            "Path is not a regular file: $source"
        }

        val target = destination.toAbsolutePath().normalize()
        Files.createDirectories(target.parent)
        Files.copy(source, target)

        val attributes =
            Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        return FileMetadata(
            kind = FileKind.FILE,
            sizeBytes = attributes.size(),
            modifiedAt = attributes.lastModifiedTime().toInstant(),
        )
    }
}
