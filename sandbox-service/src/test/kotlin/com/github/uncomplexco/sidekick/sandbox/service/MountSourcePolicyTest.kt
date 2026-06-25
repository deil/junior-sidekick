package com.github.uncomplexco.sidekick.sandbox.service

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.test.assertEquals

class MountSourcePolicyTest {
    @ParameterizedTest
    @MethodSource("prefixCases")
    fun `allowed prefix matches by path segment`(case: PrefixCase) {
        assertEquals(case.valid, isInsideAllowedPrefix(case.path, case.prefix))
    }

    companion object {
        @JvmStatic
        fun prefixCases(): Stream<PrefixCase> {
            return Stream.of(
                PrefixCase(Path.of("/data"), Path.of("/data/project"), true),
                PrefixCase(Path.of("/data"), Path.of("/data-bad"), false),
                PrefixCase(Path.of("/srv/data"), Path.of("/srv/data/project"), true),
                PrefixCase(Path.of("/srv/data"), Path.of("/srv/databad"), false),
                PrefixCase(Path.of("/srv/sidekick/data"), Path.of("/srv/sidekick/data/state/session/work"), true),
                PrefixCase(Path.of("/srv/sidekick/data"), Path.of("/srv/sidekick/data-backup/state/session"), false),
                PrefixCase(Path.of("/srv/sidekick/data/state"), Path.of("/srv/sidekick/data/state/c123/work"), true),
                PrefixCase(Path.of("/srv/sidekick/data/state"), Path.of("/srv/sidekick/data/stateful/c123"), false),
                PrefixCase(Path.of("/a/b/c"), Path.of("/a/b/c/d/e/f"), true),
                PrefixCase(Path.of("/a/b/c"), Path.of("/a/b/cd/e/f"), false),
                PrefixCase(Path.of("/home/test"), Path.of("/var/home/test"), false),
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
