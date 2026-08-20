package com.github.uncomplexco.sidekick.application.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class SidekickCoroutineScope(
    private val delegate: CoroutineScope = CoroutineScope(SupervisorJob()),
) : AutoCloseable {
    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = delegate.launch(context, block = block)

    override fun close() {
        delegate.cancel()
    }
}
