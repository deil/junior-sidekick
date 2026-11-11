package com.github.uncomplexco.sidekick.adapters.files

internal class WorkspacePatchParser(patchText: String) {
    private val lines = patchText.replace("\r\n", "\n").replace("\r", "\n").trim().lines()
    private var index = 1

    init {
        require(patchText.isNotEmpty()) { "patchText is required" }
    }

    fun parse(): List<PatchOperation> {
        require(lines != listOf("*** Begin Patch", "*** End Patch")) {
            "patch rejected: empty patch"
        }
        require(lines.first() == "*** Begin Patch" && lines.last() == "*** End Patch") {
            "apply_patch verification failed: invalid patch envelope"
        }

        val operations = mutableListOf<PatchOperation>()
        while (index < lines.lastIndex) {
            val line = lines[index++]
            when {
                line.startsWith("*** Add File: ") -> operations += readAdd(line)
                line.startsWith("*** Delete File: ") ->
                    operations +=
                        PatchOperation.DeleteFile(line.removePrefix("*** Delete File: ").trim())
                line.startsWith("*** Update File: ") -> operations += readUpdate(line)
                line.isBlank() -> Unit
                else ->
                    throw IllegalArgumentException(
                        "apply_patch verification failed: invalid patch line: $line"
                    )
            }
        }

        require(operations.isNotEmpty()) { "apply_patch verification failed: no hunks found" }

        return operations
    }

    private fun readAdd(header: String): PatchOperation.AddFile {
        val content =
            readBody().map { line ->
                require(line.startsWith("+")) {
                    "apply_patch verification failed: invalid add file line: $line"
                }
                line.removePrefix("+")
            }

        return PatchOperation.AddFile(
            header.removePrefix("*** Add File: ").trim(),
            if (content.isEmpty()) "" else content.joinToString("\n") + "\n",
        )
    }

    private fun readUpdate(header: String): PatchOperation.UpdateFile {
        val moveTo =
            if (index < lines.lastIndex && lines[index].startsWith("*** Move to: ")) {
                lines[index++].removePrefix("*** Move to: ").trim()
            } else null

        val diff = readBody()
        require(diff.isNotEmpty()) { "apply_patch verification failed: no hunks found" }

        return PatchOperation.UpdateFile(
            header.removePrefix("*** Update File: ").trim(),
            moveTo,
            diff,
        )
    }

    private fun readBody(): List<String> {
        val body = mutableListOf<String>()
        while (index < lines.lastIndex && !lines[index].startsWith("*** ")) {
            body += lines[index++]
        }

        return body
    }
}

internal sealed interface PatchOperation {
    val path: String

    data class AddFile(override val path: String, val content: String) : PatchOperation

    data class DeleteFile(override val path: String) : PatchOperation

    data class UpdateFile(
        override val path: String,
        val moveTo: String?,
        val diffLines: List<String>,
    ) : PatchOperation {
        fun applyTo(original: String): String {
            val hunks = splitHunks()
            require(hunks.isNotEmpty()) { "apply_patch verification failed: no hunks found" }

            var content = original
            for (hunk in hunks) {
                val oldBlock =
                    hunk
                        .filter { it.startsWith("-") || it.startsWith(" ") }
                        .joinToString("\n") { it.drop(1) }
                val newBlock =
                    hunk
                        .filter { it.startsWith("+") || it.startsWith(" ") }
                        .joinToString("\n") { it.drop(1) }
                content =
                    when {
                        oldBlock.isEmpty() -> insertByContext(content, hunk, newBlock)
                        content.contains(oldBlock) -> content.replaceFirst(oldBlock, newBlock)
                        else ->
                            throw IllegalArgumentException(
                                "apply_patch verification failed: Failed to apply update to $path"
                            )
                    }
            }

            return if (diffLines.any { it.startsWith("+") } && !content.endsWith("\n")) "$content\n"
            else content
        }

        private fun splitHunks(): List<List<String>> {
            val hunks = mutableListOf<MutableList<String>>()
            var current: MutableList<String>? = null
            for (line in diffLines) {
                when {
                    line.startsWith("@@") -> {
                        current = mutableListOf()
                        hunks += current
                    }
                    current != null -> current += line
                }
            }

            return hunks
        }

        private fun insertByContext(content: String, hunk: List<String>, newBlock: String): String {
            val contextLines = hunk.filter { it.startsWith(" ") }.map { it.drop(1) }
            if (contextLines.isEmpty()) return content + newBlock

            val anchor = contextLines.joinToString("\n")
            val index = content.indexOf(anchor)
            require(index >= 0) { "apply_patch verification failed: Failed to apply insert hunk" }
            val anchorEnd = index + anchor.length

            return content.substring(0, anchorEnd) + "\n" + newBlock + content.substring(anchorEnd)
        }
    }
}
