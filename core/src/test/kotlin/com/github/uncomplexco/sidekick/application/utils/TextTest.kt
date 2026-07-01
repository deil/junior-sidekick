package com.github.uncomplexco.sidekick.application.utils

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals

class TextTest {
    @ParameterizedTest
    @MethodSource("trimStartCases")
    fun `trims text from start`(
        text: String,
        length: Int,
        prefix: String?,
        expected: String,
    ) {
        assertEquals(expected, trimStart(text, length, prefix))
    }

    @ParameterizedTest
    @MethodSource("trimEndCases")
    fun `trims text from end`(
        text: String,
        length: Int,
        suffix: String?,
        expected: String,
    ) {
        assertEquals(expected, trimEnd(text, length, suffix))
    }

    companion object {
        @JvmStatic
        fun trimStartCases(): Stream<Array<Any?>> =
            Stream.of(
                arrayOf("short", 10, "...", "short"),
                arrayOf("abcdef", 4, null, "cdef"),
                arrayOf("abcdef", 5, "...", "...ef"),
                arrayOf("abcdef", 2, "...", ".."),
            )

        @JvmStatic
        fun trimEndCases(): Stream<Array<Any?>> =
            Stream.of(
                arrayOf("short", 10, "...", "short"),
                arrayOf("abcdef", 4, null, "abcd"),
                arrayOf("abcdef", 5, "...", "ab..."),
                arrayOf("abcdef", 2, "...", ".."),
            )
    }
}
