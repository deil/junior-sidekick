package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.fail
import com.slack.api.methods.SlackApiException
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

private const val SLACK_REQUEST_ATTEMPTS = 3
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private val log = LoggerFactory.getLogger("sidekick.slack-tools")

internal fun <T> withSlackRetries(
    operation: String,
    attempts: Int = SLACK_REQUEST_ATTEMPTS,
    action: () -> T,
): T {
    for (attempt in 1..attempts) {
        log.info("{} attempt={}", operation, attempt)
        try {
            return action()
        } catch (error: SlackApiException) {
            val status = error.response.code
            if (
                attempt == attempts ||
                    (status != HTTP_TOO_MANY_REQUESTS && status < HTTP_SERVER_ERROR)
            ) {
                fail("$operation failed: ${error.error?.error ?: "HTTP $status"}.")
            }
            log.warn("{} failed with HTTP {}; retrying", operation, status)
            if (status == HTTP_TOO_MANY_REQUESTS) {
                val seconds = error.response.header("Retry-After")?.toLongOrNull() ?: 1L
                Thread.sleep(TimeUnit.SECONDS.toMillis(seconds.coerceAtLeast(0)))
            }
        } catch (error: IOException) {
            if (attempt == attempts) fail("$operation failed due to a Slack connection error.")
            log.warn("{} failed due to a connection error; retrying", operation, error)
        }
    }
    error("Slack request requires at least one attempt")
}
