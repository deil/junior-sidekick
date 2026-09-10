package com.github.uncomplexco.sidekick.application.runtime

import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SidekickCoroutineScopeTest {
    @Test
    fun `closing scope cancels launched work`() = runBlocking {
        val scope = SidekickCoroutineScope()
        val job = scope.launch { awaitCancellation() }

        scope.close()
        job.join()

        assertTrue(job.isCancelled)
    }
}
