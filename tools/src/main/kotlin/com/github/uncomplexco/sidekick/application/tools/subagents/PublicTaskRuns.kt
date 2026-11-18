package com.github.uncomplexco.sidekick.application.tools.subagents

import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

fun interface IsolatedSubagentRunner {
    suspend fun runIsolated(
        runId: String,
        subagent: Subagent,
        prompt: String,
    ): AgentRunResult<String>
}

@Component
class PublicTaskRuns(
    private val subagents: Subagents,
    private val runner: IsolatedSubagentRunner,
    private val scope: SidekickCoroutineScope,
) {
    sealed interface Submission {
        data class Accepted(val id: UUID) : Submission

        data object UnknownSubagent : Submission

        data object AtCapacity : Submission
    }

    sealed interface Status {
        data object Queued : Status

        data object Running : Status

        data class Completed(val result: String) : Status

        data object Failed : Status
    }

    private class Run(val id: UUID, val clientId: String) {
        var status: Status = Status.Queued
        var finishedAt: TimeMark? = null

        fun isExpired(): Boolean {
            val finished = finishedAt ?: return false
            return finished.elapsedNow() >= RESULT_RETENTION
        }
    }

    private val runs = mutableMapOf<UUID, Run>()
    private val workers = Semaphore(MAX_CONCURRENT_RUNS)
    private val log = LoggerFactory.getLogger(PublicTaskRuns::class.java)

    fun submit(clientId: String, subagentType: String, prompt: String): Submission {
        val subagent = subagents.findBuiltIn(subagentType) ?: return Submission.UnknownSubagent
        val run = Run(UUID.randomUUID(), clientId)
        synchronized(runs) {
            runs.values.removeIf(Run::isExpired)
            if (runs.size >= MAX_RETAINED_RUNS) return Submission.AtCapacity
            runs[run.id] = run
        }

        log.info(
            "Queued API task runId={} clientId={} subagentType={}",
            run.id,
            clientId,
            subagentType,
        )
        scope.launch { execute(run, subagent, prompt) }
        return Submission.Accepted(run.id)
    }

    fun status(clientId: String, id: UUID): Status? =
        synchronized(runs) {
            val run = runs[id] ?: return null
            if (run.isExpired()) {
                runs.remove(id)
                return null
            }
            run.takeIf { it.clientId == clientId }?.status
        }

    private suspend fun execute(run: Run, subagent: Subagent, prompt: String) {
        try {
            workers.withPermit {
                synchronized(runs) { run.status = Status.Running }
                log.info("Started API task runId={}", run.id)

                val result =
                    withTimeoutOrNull(EXECUTION_TIMEOUT) {
                        runner.runIsolated(run.id.toString(), subagent, prompt)
                    }
                val status =
                    if (result is AgentRunResult.Completed) {
                        Status.Completed(result.output)
                    } else {
                        Status.Failed
                    }
                finish(run, status)
            }
        } catch (e: CancellationException) {
            finish(run, Status.Failed)
            throw e
        } catch (e: Exception) {
            log.warn("Unexpected API task failure runId={} kind={}", run.id, e.javaClass.simpleName)
            finish(run, Status.Failed)
        }
    }

    private fun finish(run: Run, status: Status) {
        synchronized(runs) {
            run.status = status
            run.finishedAt = TimeSource.Monotonic.markNow()
        }
        log.info("Finished API task runId={} completed={}", run.id, status is Status.Completed)
    }

    private companion object {
        const val MAX_CONCURRENT_RUNS = 4
        const val MAX_RETAINED_RUNS = 256
        val EXECUTION_TIMEOUT = 2.minutes
        val RESULT_RETENTION = 1.hours
    }
}
