package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.ports.filesystem.GrepMatch
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadOptions
import com.github.uncomplexco.sidekick.ports.filesystem.TextReadResult
import java.io.BufferedReader
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.Path

internal class LocalTextFiles {
    fun write(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content)
    }

    fun read(path: Path, options: TextReadOptions): TextReadResult {
        val buffer = TextReadBuffer(options)

        Files.newBufferedReader(path).use { reader ->
            var codePoint = readCodePoint(reader)
            while (codePoint != -1) {
                if (!buffer.append(codePoint)) return buffer.result(byteLimitReached = true)
                codePoint = readCodePoint(reader)
            }
        }

        return buffer.result(byteLimitReached = false)
    }

    fun isBinary(path: Path): Boolean {
        val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension in BINARY_FILE_EXTENSIONS) return true

        val sample = Files.newInputStream(path).use { it.readNBytes(BINARY_SAMPLE_BYTES) }

        return sample.isNotEmpty() && isBinarySample(sample)
    }

    fun grep(path: Path, displayPath: String, pattern: Regex): List<GrepMatch> {
        if (isBinary(path)) return emptyList()

        val modifiedAt = Files.getLastModifiedTime(path).toInstant()
        val lines =
            try {
                Files.readAllLines(path)
            } catch (_: MalformedInputException) {
                emptyList()
            }

        return lines.mapIndexedNotNull { index, text ->
            if (pattern.containsMatchIn(text)) {
                GrepMatch(displayPath, index + 1, text, modifiedAt)
            } else null
        }
    }

    private fun isBinarySample(sample: ByteArray): Boolean {
        val values = sample.map { it.toInt() and 0xFF }
        if (0 in values) return true

        val nonPrintable = values.count {
            it < HORIZONTAL_TAB || (it > CARRIAGE_RETURN && it < SPACE)
        }

        return nonPrintable.toDouble() / sample.size > MAX_NON_PRINTABLE_RATIO
    }

    private fun readCodePoint(reader: BufferedReader): Int {
        val first = reader.read()

        return if (first != -1 && Character.isHighSurrogate(first.toChar())) {
            Character.toCodePoint(first.toChar(), reader.read().toChar())
        } else first
    }

    private companion object {
        const val BINARY_SAMPLE_BYTES = 4096
        const val HORIZONTAL_TAB = 9
        const val CARRIAGE_RETURN = 13
        const val SPACE = 32
        const val MAX_NON_PRINTABLE_RATIO = 0.3
        val BINARY_FILE_EXTENSIONS =
            setOf(
                "zip",
                "tar",
                "gz",
                "exe",
                "dll",
                "so",
                "class",
                "jar",
                "war",
                "7z",
                "doc",
                "docx",
                "xls",
                "xlsx",
                "ppt",
                "pptx",
                "odt",
                "ods",
                "odp",
                "bin",
                "dat",
                "obj",
                "o",
                "a",
                "lib",
                "wasm",
                "pyc",
                "pyo",
            )
    }
}

private class TextReadBuffer(private val options: TextReadOptions) {
    private val content = StringBuilder()
    private val endLine = options.maxLines?.let { options.offset.toLong() + it } ?: Long.MAX_VALUE
    private var totalLines = 0
    private var previous = -1
    private var bytes = 0
    private var truncated = false

    fun append(codePoint: Int): Boolean {
        val afterLineBreak =
            previous == '\n'.code || (previous == '\r'.code && codePoint != '\n'.code)
        if (totalLines == 0 || afterLineBreak) totalLines += 1
        previous = codePoint

        if (totalLines >= endLine) truncated = true

        if (totalLines >= options.offset && totalLines < endLine) {
            val byteCount = utf8Bytes(codePoint)
            if (options.maxBytes != null && byteCount > options.maxBytes - bytes) return false

            content.appendCodePoint(codePoint)
            bytes += byteCount
        }

        return true
    }

    fun result(byteLimitReached: Boolean): TextReadResult {
        require(totalLines >= options.offset || (totalLines == 0 && options.offset == 1)) {
            "Offset ${options.offset} is out of range for this file ($totalLines lines)"
        }

        return TextReadResult(
            content.toString(),
            truncated || byteLimitReached,
            if (byteLimitReached) null else totalLines,
        )
    }

    private fun utf8Bytes(codePoint: Int): Int =
        when {
            codePoint <= MAX_ONE_BYTE_CODE_POINT -> 1
            codePoint <= MAX_TWO_BYTE_CODE_POINT -> 2
            codePoint <= Char.MAX_VALUE.code -> THREE_BYTE_SEQUENCE_LENGTH
            else -> FOUR_BYTE_SEQUENCE_LENGTH
        }

    private companion object {
        const val MAX_ONE_BYTE_CODE_POINT = 0x7F
        const val MAX_TWO_BYTE_CODE_POINT = 0x7FF
        const val THREE_BYTE_SEQUENCE_LENGTH = 3
        const val FOUR_BYTE_SEQUENCE_LENGTH = 4
    }
}
