package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths.Companion.PROJECT_ROOT
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryEntry
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPage
import com.github.uncomplexco.sidekick.ports.filesystem.DirectoryPageOptions
import com.github.uncomplexco.sidekick.ports.filesystem.FileKind
import com.github.uncomplexco.sidekick.ports.filesystem.FileMetadata
import com.github.uncomplexco.sidekick.ports.filesystem.GlobMatch
import com.github.uncomplexco.sidekick.ports.filesystem.GlobResult
import com.github.uncomplexco.sidekick.ports.filesystem.GrepMatch
import com.github.uncomplexco.sidekick.ports.filesystem.GrepResult
import com.github.uncomplexco.sidekick.ports.filesystem.PatchResult
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadOptions
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadResult
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFile
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystem
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class LocalWorkspaceFilesystem(private val virtualPaths: VirtualPaths) : WorkspaceFilesystem {
    private val logger = LoggerFactory.getLogger(LocalWorkspaceFilesystem::class.java)
    private val resolve = LocalWorkspacePaths(virtualPaths)::resolve
    private val textFiles = LocalTextFiles()
    private val patches = LocalWorkspacePatches(resolve, textFiles)
    private val transfers = LocalWorkspaceTransfers(resolve)

    override suspend fun seedProject(projectId: String, source: Path) {
        withContext(Dispatchers.IO) {
            val destination = resolve(PROJECT_ROOT)
            Files.walk(source).use { paths ->
                paths.forEach { file ->
                    val relative = source.relativize(file)
                    if (relative.toString().isBlank()) {
                        return@forEach
                    }

                    val target = destination.resolve(relative)
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target)
                    } else if (!Files.exists(target)) {
                        Files.createDirectories(target.parent)
                        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
            logger.info("Project workspace seeded project_id={}", projectId)
        }
    }

    override suspend fun upload(
        projectId: String,
        sessionId: String,
        source: Path,
    ): WorkspaceFile =
        withContext(Dispatchers.IO) {
            val uploaded = transfers.upload(source)
            logger.info(
                "Workspace file uploaded project_id={} session_id={} destination={}",
                projectId,
                sessionId,
                uploaded.path,
            )
            uploaded
        }

    override suspend fun download(
        projectId: String,
        sessionId: String,
        source: String,
        destination: Path,
    ): FileMetadata =
        withContext(Dispatchers.IO) {
            val metadata = transfers.download(source, destination)
            logger.info(
                "Workspace file downloaded project_id={} session_id={} source={}",
                projectId,
                sessionId,
                source,
            )
            metadata
        }

    override suspend fun stat(projectId: String, path: String): FileMetadata? =
        withContext(Dispatchers.IO) {
            val target = resolve(path)

            val attributes =
                try {
                    Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (_: NoSuchFileException) {
                    return@withContext null
                }
            require(attributes.isRegularFile || attributes.isDirectory) {
                "Path is not a regular file or directory: $path"
            }

            FileMetadata(
                kind = if (attributes.isDirectory) FileKind.DIRECTORY else FileKind.FILE,
                sizeBytes = attributes.size(),
                modifiedAt = attributes.lastModifiedTime().toInstant(),
            )
        }

    override suspend fun readText(
        projectId: String,
        path: String,
        options: TextReadOptions,
    ): TextReadResult =
        withContext(Dispatchers.IO) {
            require(options.offset >= 1) { "`offset` must be greater than or equal to 1" }
            require(options.maxLines == null || options.maxLines >= 0) {
                "`maxLines` must be non-negative"
            }
            require(options.maxBytes == null || options.maxBytes >= 0) {
                "`maxBytes` must be non-negative"
            }

            val target = resolve(path)
            require(Files.isRegularFile(target, NOFOLLOW_LINKS)) {
                "Path is not a regular file: $path"
            }
            require(!textFiles.isBinary(target)) { "Cannot read binary file: $path" }

            textFiles.read(target, options)
        }

    override suspend fun writeText(projectId: String, path: String, content: String) {
        withContext(Dispatchers.IO) {
            val target = resolve(path)
            require(!Files.isDirectory(target)) { "Path is a directory, not a file: $path" }

            textFiles.write(target, content)

            logger.info("Workspace file written project_id={} path={}", projectId, path)
        }
    }

    override suspend fun editText(
        projectId: String,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            require(path.isNotBlank()) { "path is required" }
            require(oldString != newString) {
                "No changes to apply: oldString and newString are identical."
            }

            val target = resolve(path)
            require(!Files.isDirectory(target)) { "Path is a directory, not a file: $path" }

            val updated =
                if (oldString.isEmpty()) {
                    newString
                } else {
                    require(Files.exists(target)) { "File $path not found" }

                    val original = Files.readString(target)
                    val occurrences = Regex(Regex.escape(oldString)).findAll(original).count()
                    require(occurrences > 0) { "oldString not found in file" }
                    require(replaceAll || occurrences == 1) {
                        "Found multiple matches for oldString. Provide more surrounding context " +
                            "to make the match unique."
                    }

                    if (replaceAll) original.replace(oldString, newString)
                    else original.replaceFirst(oldString, newString)
                }

            textFiles.write(target, updated)

            logger.info("Workspace file edited project_id={} path={}", projectId, path)
        }
    }

    override suspend fun applyPatch(
        projectId: String,
        path: String,
        patchText: String,
    ): PatchResult = withContext(Dispatchers.IO) { patches.apply(projectId, path, patchText) }

    override suspend fun listDirectory(
        projectId: String,
        path: String,
        options: DirectoryPageOptions,
    ): DirectoryPage =
        withContext(Dispatchers.IO) {
            require(options.offset >= 1) { "`offset` must be greater than or equal to 1" }
            require(options.limit == null || options.limit >= 0) { "`limit` must be non-negative" }

            val normalizedPath = if (path == "/") path else path.trimEnd('/')

            val entries =
                when (normalizedPath) {
                    "/" ->
                        virtualPaths.roots
                            .map {
                                DirectoryEntry(
                                    it.virtual.trimStart('/').substringBefore('/'),
                                    FileKind.DIRECTORY,
                                )
                            }
                            .distinct()
                    VirtualPaths.DATA_ROOT ->
                        virtualPaths.roots
                            .filter { it.virtual.startsWith("${VirtualPaths.DATA_ROOT}/") }
                            .map {
                                DirectoryEntry(
                                    it.virtual
                                        .removePrefix("${VirtualPaths.DATA_ROOT}/")
                                        .substringBefore('/'),
                                    FileKind.DIRECTORY,
                                )
                            }
                            .distinct()
                    else ->
                        Files.list(resolve(normalizedPath)).use { files ->
                            files
                                .map {
                                    DirectoryEntry(
                                        it.fileName.toString(),
                                        if (Files.isDirectory(it)) FileKind.DIRECTORY
                                        else FileKind.FILE,
                                    )
                                }
                                .toList()
                        }
                }.sortedBy { it.name + if (it.kind == FileKind.DIRECTORY) "/" else "" }

            val remaining = entries.drop(options.offset - 1)
            val page = options.limit?.let { remaining.take(it) } ?: remaining

            DirectoryPage(page, entries.size, page.size < remaining.size)
        }

    override suspend fun glob(projectId: String, path: String, pattern: String): GlobResult =
        withContext(Dispatchers.IO) {
            val normalizedPath = if (path == "/") path else path.trimEnd('/')
            if (normalizedPath == "/" || normalizedPath == VirtualPaths.DATA_ROOT) {
                val roots =
                    virtualPaths.roots.filter {
                        normalizedPath == "/" || it.virtual.startsWith("$normalizedPath/")
                    }

                val results =
                    roots
                        .filter {
                            stat(projectId, it.virtual)?.kind == FileKind.DIRECTORY
                        }
                        .map { glob(projectId, it.virtual, pattern) }

                val matches =
                    results
                        .flatMap { it.matches }
                        .sortedWith(
                            compareByDescending<GlobMatch> { it.modifiedAt }.thenBy { it.path }
                        )

                return@withContext GlobResult(
                    matches.take(MAX_GLOB_RESULTS),
                    results.any { it.truncated } || matches.size > MAX_GLOB_RESULTS,
                )
            }

            val search = resolve(normalizedPath)
            require(!Files.isRegularFile(search)) { "glob path must be a directory: $path" }

            val matchers = globMatchers(search, pattern)

            val matches =
                Files.walk(search).use { files ->
                    files
                        .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                        .filter { file -> file.none { it.toString() == ".git" } }
                        .filter { file ->
                            val relative = search.relativize(file)
                            matchers.any { it.matches(relative) }
                        }
                        .map { file ->
                            GlobMatch(
                                "$normalizedPath/${search.relativize(file)}",
                                Files.getLastModifiedTime(file).toInstant(),
                            )
                        }
                        .toList()
                        .sortedWith(
                            compareByDescending<GlobMatch> { it.modifiedAt }.thenBy { it.path }
                        )
                }

            GlobResult(matches.take(MAX_GLOB_RESULTS), matches.size > MAX_GLOB_RESULTS)
        }

    override suspend fun grep(
        projectId: String,
        path: String,
        pattern: String,
        include: String?,
    ): GrepResult =
        withContext(Dispatchers.IO) {
            require(pattern.isNotBlank()) { "pattern is required" }

            val normalizedPath = if (path == "/") path else path.trimEnd('/')
            val regex = Regex(pattern)

            if (normalizedPath == "/" || normalizedPath == VirtualPaths.DATA_ROOT) {
                val roots =
                    virtualPaths.roots.filter {
                        normalizedPath == "/" || it.virtual.startsWith("$normalizedPath/")
                    }

                val results =
                    roots
                        .filter {
                            stat(projectId, it.virtual)?.kind == FileKind.DIRECTORY
                        }
                        .map { grep(projectId, it.virtual, pattern, include) }

                val matches =
                    results
                        .flatMap { it.matches }
                        .sortedWith(
                            compareByDescending<GrepMatch> { it.modifiedAt }
                                .thenBy { it.path }
                                .thenBy { it.line }
                        )
                val total = results.sumOf { it.total }

                return@withContext GrepResult(
                    matches.take(MAX_GREP_RESULTS),
                    total,
                    total > MAX_GREP_RESULTS,
                )
            }

            val search = resolve(normalizedPath)
            val directory = Files.isDirectory(search)
            val cwd = if (directory) search else resolve("$normalizedPath/..")
            val displayRoot =
                if (directory) normalizedPath else normalizedPath.substringBeforeLast('/')
            val includeMatchers = include?.takeIf { it.isNotBlank() }?.let { globMatchers(cwd, it) }

            val candidates = grepCandidates(search, cwd)

            val matches =
                candidates
                    .filter { file ->
                        includeMatchers == null ||
                            includeMatchers.any { it.matches(cwd.relativize(file)) }
                    }
                    .flatMap { file ->
                        textFiles.grep(file, "$displayRoot/${cwd.relativize(file)}", regex)
                    }
                    .sortedWith(
                        compareByDescending<GrepMatch> { it.modifiedAt }
                            .thenBy { it.path }
                            .thenBy { it.line }
                    )

            GrepResult(
                matches.take(MAX_GREP_RESULTS),
                matches.size,
                matches.size > MAX_GREP_RESULTS,
            )
        }

    private companion object {
        const val MAX_GLOB_RESULTS = 100
        const val MAX_GREP_RESULTS = 100
    }
}
