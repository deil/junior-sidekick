package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPath
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.parseVirtualPath
import com.github.uncomplexco.sidekick.application.chat.ReplyAttachment
import com.github.uncomplexco.sidekick.application.turn.TurnExecutor.Companion.MAX_MESSAGE_FILES
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class ReplyAttachmentCollector(
    private val virtualPaths: VirtualPaths,
) {
    private val attachments = mutableListOf<ReplyAttachment>()

    fun attach(
        path: VirtualPath,
        name: String?,
        mimeType: String?,
    ): ReplyAttachment {
        require(attachments.size < MAX_MESSAGE_FILES) { "A reply can include at most $MAX_MESSAGE_FILES files." }

        val source = resolveRegularFile(path)
        val filename = name?.trim()?.also(::requireFilename) ?: source.fileName.toString()
        val staged = stage(source, filename)
        val attachment =
            ReplyAttachment(
                path = staged,
                name = filename,
                mimeType = mimeType?.trim()?.takeIf(String::isNotBlank) ?: inferMimeType(source),
                bytes = Files.size(staged),
            )
        attachments += attachment
        return attachment
    }

    fun collected(): List<ReplyAttachment> = attachments.toList()

    fun clear() {
        attachments.forEach { Files.deleteIfExists(it.path) }
        attachments.clear()
    }

    private fun resolveRegularFile(path: VirtualPath): Path {
        val root =
            virtualPaths.roots.firstOrNull { path == it.virtual || path.startsWith("${it.virtual}/") }
                ?: error("Unknown virtual path: $path")
        val target = Path.of(parseVirtualPath(path, virtualPaths)).toAbsolutePath().normalize()
        val normalizedRoot = root.real.toAbsolutePath().normalize()
        require(target.startsWith(normalizedRoot)) { "Path escapes workspace: $path" }

        var current = normalizedRoot
        normalizedRoot.relativize(target).forEach {
            current = current.resolve(it)
            require(!Files.isSymbolicLink(current)) { "Path must not contain symbolic links: $path" }
        }
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "Path is not a regular file: $path" }
        require(Files.size(target) <= MAX_REPLY_ATTACHMENT_BYTES) { "File exceeds the 10 MiB reply attachment limit: $path" }
        return target
    }

    private fun stage(
        source: Path,
        filename: String,
    ): Path {
        val stagingDirectory = Files.createDirectories(virtualPaths.sessionRoot.resolve(STAGING_DIRECTORY))
        val target = stagingDirectory.resolve("${UUID.randomUUID()}-$filename")
        try {
            Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
        } catch (error: Exception) {
            Files.deleteIfExists(target)
            throw error
        }
        return target
    }

    private fun inferMimeType(path: Path): String = Files.probeContentType(path) ?: DEFAULT_MIME_TYPE

    private fun requireFilename(name: String) {
        require(name.isNotBlank() && Path.of(name).fileName.toString() == name) { "Name must be a filename without path separators." }
    }

    companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val MAX_REPLY_ATTACHMENT_BYTES = 10L * 1024 * 1024
        const val STAGING_DIRECTORY = "reply-attachments"
    }
}
