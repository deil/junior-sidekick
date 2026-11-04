package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import org.slf4j.LoggerFactory

internal class DiscordProgressMessages(
    private val scope: SidekickCoroutineScope,
    private val send: suspend (String) -> DiscordSentMessage,
    private val edit: suspend (String, String) -> Unit,
) {
    private companion object {
        const val REFRESH_INTERVAL_MS = 60_000L
        const val TODO_FOOTER_PREFIX = "\n\n_todos as of "
        const val TRUNCATION_NOTICE = "\n\n… (todo list truncated)"
        val log = LoggerFactory.getLogger(DiscordProgressMessages::class.java)
    }

    private val mutex = Mutex()
    private var state: State? = null

    private class State {
        val messageIds = mutableMapOf<String, String>()
        val refreshJobs = mutableMapOf<String, Job>()
    }

    fun begin() {
        end()
        state = State()
    }

    fun end() {
        state?.refreshJobs?.values?.forEach { it.cancel() }
        state = null
    }

    suspend fun report(text: String, key: String, refresh: (() -> String)?) = mutex.withLock {
        val current = state ?: return@withLock
        current.refreshJobs.remove(key)?.cancel()
        publish(current, text, key)
        if (refresh != null && state === current) {
            current.refreshJobs[key] = scope.launch {
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    mutex.withLock {
                        if (state === current && isActive) publish(current, refresh(), key)
                    }
                }
            }
        }
    }

    private suspend fun publish(current: State, text: String, key: String) {
        val content = capText(text)
        try {
            val messageId = current.messageIds[key]
            if (messageId == null) {
                val sent = send(content)
                current.messageIds[key] = sent.id
                log.info("Posted Discord progress message {}", sent.id)
            } else {
                edit(messageId, content)
                log.info("Updated Discord progress message {}", messageId)
            }
        } catch (error: IOException) {
            log.warn("Discord progress message delivery failed", error)
        } catch (error: ErrorResponseException) {
            log.warn("Discord progress message delivery failed", error)
        } catch (error: IllegalArgumentException) {
            log.warn("Discord progress message rejected", error)
        }
    }

    private fun capText(text: String): String {
        if (text.length <= DISCORD_MESSAGE_LIMIT) return text

        val footerStart = text.lastIndexOf(TODO_FOOTER_PREFIX)
        val footer = if (footerStart >= 0) text.substring(footerStart) else ""
        var end = DISCORD_MESSAGE_LIMIT - TRUNCATION_NOTICE.length - footer.length
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + TRUNCATION_NOTICE + footer
    }
}
