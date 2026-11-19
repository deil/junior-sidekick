package com.github.uncomplexco.sidekick.sandbox.service

import java.nio.file.Path
import java.util.stream.Stream
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MountSourcePolicyTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("prefixCases")
    fun `allowed prefix matches by path segment`(case: PrefixCase) {
        assertEquals(case.valid, isInsideAllowedPrefix(case.path, case.prefix))
    }

    companion object {
        @JvmStatic
        fun prefixCases(): Stream<PrefixCase> {
            return Stream.of(
                PrefixCase(Path.of("/a/b/c"), Path.of("/a/b/c/d/e/f"), true),
                PrefixCase(Path.of("/a/b/c"), Path.of("/a/b/cd/e/f"), false),
                PrefixCase(Path.of("/home/test"), Path.of("/tmp/home/test/nested"), false),
            )
        }
    }
}

data class PrefixCase(
    val prefix: Path,
    val path: Path,
    val valid: Boolean,
)
