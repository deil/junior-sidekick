package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.ports.filesystem.PatchChange
import com.github.uncomplexco.sidekick.ports.filesystem.PatchChangeKind
import com.github.uncomplexco.sidekick.ports.filesystem.PatchResult
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

internal class LocalWorkspacePatches(
    private val resolve: (String) -> Path,
    private val textFiles: LocalTextFiles,
) {
    private val logger = LoggerFactory.getLogger(LocalWorkspacePatches::class.java)

    fun apply(projectId: String, path: String, patchText: String): PatchResult {
        val base = resolve(path)
        require(Files.isDirectory(base)) { "Patch path must be a directory: $path" }

        val operations = WorkspacePatchParser(patchText).parse()

        val changes = operations.map { operation ->
            val target = resolvePath(base, path, operation.path)
            val change =
                when (operation) {
                    is PatchOperation.AddFile -> {
                        textFiles.write(target, operation.content)
                        PatchChange(displayPath(base, path, target), PatchChangeKind.ADDED)
                    }
                    is PatchOperation.DeleteFile -> {
                        requirePatchFile(target, operation.path)
                        Files.delete(target)
                        PatchChange(displayPath(base, path, target), PatchChangeKind.DELETED)
                    }
                    is PatchOperation.UpdateFile -> update(base, path, target, operation)
                }

            logger.info(
                "Workspace patch applied project_id={} path={} kind={}",
                projectId,
                change.path,
                change.kind,
            )

            change
        }

        return PatchResult(changes)
    }

    private fun update(
        base: Path,
        path: String,
        source: Path,
        operation: PatchOperation.UpdateFile,
    ): PatchChange {
        requirePatchFile(source, operation.path)

        val updated = operation.applyTo(Files.readString(source))
        val destination = operation.moveTo?.let { resolvePath(base, path, it) }

        textFiles.write(destination ?: source, updated)
        if (destination != null) Files.delete(source)

        return PatchChange(displayPath(base, path, destination ?: source), PatchChangeKind.MODIFIED)
    }

    private fun resolvePath(base: Path, path: String, input: String): Path {
        val virtualPath = if (input.startsWith('/')) input else "${path.trimEnd('/')}/$input"
        val target = resolve(virtualPath)
        require(target.startsWith(base)) { "Path escapes working directory: $input" }

        return target
    }

    private fun requirePatchFile(target: Path, path: String) {
        require(Files.exists(target) && !Files.isDirectory(target)) {
            "apply_patch verification failed: Failed to read file to update: $path"
        }
    }

    private fun displayPath(base: Path, path: String, target: Path): String =
        "${path.trimEnd('/')}/${base.relativize(target)}"
}
