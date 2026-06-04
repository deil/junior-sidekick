package com.github.uncomplexco.sidekick.application.core

import kotlinx.serialization.Serializable
import java.nio.file.Path

enum class MessageRole {
    USER,
    ASSISTANT,
}

@Serializable
data class MessageAuthor(
    val username: String,
    val fullName: String?,
)

typealias AbsolutePath = String
typealias VirtualPath = String

fun sessionPath(
    sessionRoot: Path,
    path: AbsolutePath,
): VirtualPath = sessionRoot.relativize(Path.of(path)).toString().toSessionBasedPath()

fun String.toSessionBasedPath(): VirtualPath = "session:/$this"
