package com.github.uncomplexco.sidekick.ports.filesystem

import java.time.Instant

enum class PatchChangeKind {
    ADDED,
    DELETED,
    MODIFIED,
}

data class PatchChange(val path: String, val kind: PatchChangeKind)

data class PatchResult(val changes: List<PatchChange>)

enum class FileKind {
    FILE,
    DIRECTORY,
}

data class FileMetadata(
    val kind: FileKind,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

data class WorkspaceFile(val path: String, val metadata: FileMetadata)

data class TextReadOptions(
    val offset: Int = 1,
    val maxLines: Int? = null,
    val maxBytes: Int? = null,
)

data class TextReadResult(
    val content: String,
    val truncated: Boolean,
    val totalLines: Int?,
    val lastLineTruncated: Boolean = false,
)

data class DirectoryPageOptions(val offset: Int = 1, val limit: Int? = null)

data class DirectoryEntry(val name: String, val kind: FileKind)

data class DirectoryPage(
    val entries: List<DirectoryEntry>,
    val total: Int,
    val truncated: Boolean,
)

data class GlobMatch(val path: String, val modifiedAt: Instant)

data class GlobResult(val matches: List<GlobMatch>, val truncated: Boolean)

data class GrepMatch(
    val path: String,
    val line: Int,
    val text: String,
    val modifiedAt: Instant,
)

data class GrepResult(val matches: List<GrepMatch>, val total: Int, val truncated: Boolean)
