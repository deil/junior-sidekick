package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.chat.SubagentProgress
import com.github.uncomplexco.sidekick.application.chat.SubagentStatus
import com.github.uncomplexco.sidekick.application.chat.TodoProgress
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.utils.Loggers
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class SlackProgressMessages(
    private val client: MethodsClient,
    private val channelId: String,
    threadTs: String?,
    private val scope: SidekickCoroutineScope,
) {
    private companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
    }

    private val lock = Any()
    private val timestamps = mutableMapOf<String, String>()
    private val refreshJobs = mutableMapOf<String, Job>()
    private var active = false
    private var todos: TodoProgress? = null
    private var todoRefresh: (() -> TodoProgress)? = null
    private val subagents = linkedMapOf<String, SubagentProgress>()
    var threadTs: String? = threadTs
        private set

    fun begin() =
        synchronized(lock) {
            end()
            timestamps.clear()
            todos = null
            todoRefresh = null
            subagents.clear()
            active = true
        }

    fun end() =
        synchronized(lock) {
            active = false
            refreshJobs.values.forEach { it.cancel() }
            refreshJobs.clear()
        }

    fun report(text: String, key: String, refresh: (() -> String)?) =
        synchronized(lock) {
            if (!active) return@synchronized

            refreshJobs.remove(key)?.cancel()
            publish(text, key)
            if (refresh != null) {
                refreshJobs[key] = scope.launch {
                    while (isActive) {
                        delay(REFRESH_INTERVAL_MS)
                        synchronized(lock) {
                            if (active && isActive) publish(refresh(), key)
                        }
                    }
                }
            }
        }

    fun reportTodos(progress: TodoProgress, refresh: (() -> TodoProgress)?) =
        synchronized(lock) {
            if (!active) return@synchronized

            todos = progress
            todoRefresh = refresh
            report(renderTodos(), "todos", refresh?.let { { renderTodos() } })
        }

    fun reportSubagent(progress: SubagentProgress) =
        synchronized(lock) {
            if (!active) return@synchronized

            subagents[progress.id] = progress
            publish(renderTodos(), "todos")
        }

    private fun renderTodos(): String {
        val current = todoRefresh?.invoke() ?: todos
        val agents =
            if (subagents.isEmpty()) null
            else
                "*Subagents*\n" +
                    subagents.values.joinToString("\n") { agent ->
                        val (icon, detail) =
                            when (agent.status) {
                                SubagentStatus.ACTIVE -> "∗" to agent.type
                                SubagentStatus.COMPLETED -> "✓" to agent.type
                                SubagentStatus.FAILED -> "×" to "${agent.type}, failed"
                                SubagentStatus.CANCELLED -> "×" to "${agent.type}, cancelled"
                            }
                        "$icon ${agent.description} ($detail)"
                    }
        return listOfNotNull(current?.text, agents, current?.statusLine).joinToString("\n\n")
    }

    private fun publish(text: String, key: String) {
        try {
            val messageTs = timestamps[key]
            if (messageTs == null) {
                val response = client.chatPostMessage { req ->
                    req.channel(channelId)
                    threadTs?.let { req.threadTs(it) }
                    req.text(text)
                }
                if (!response.isOk) {
                    Loggers.SLACK.warn("Slack progress message post failed: {}", response.error)
                    return
                }
                timestamps[key] = response.ts
                if (threadTs == null) threadTs = response.ts
                Loggers.SLACK.info(
                    "Posted Slack progress message in {}: {}",
                    channelId,
                    response.ts,
                )
            } else {
                val response = client.chatUpdate { req ->
                    req.channel(channelId)
                    req.ts(messageTs)
                    req.text(text)
                }
                if (!response.isOk) {
                    Loggers.SLACK.warn("Slack progress message update failed: {}", response.error)
                } else {
                    Loggers.SLACK.info(
                        "Updated Slack progress message in {}: {}",
                        channelId,
                        messageTs,
                    )
                }
            }
        } catch (error: IOException) {
            Loggers.SLACK.warn("Slack progress message delivery failed", error)
        } catch (error: SlackApiException) {
            Loggers.SLACK.warn("Slack progress message delivery failed", error)
        }
    }
}
