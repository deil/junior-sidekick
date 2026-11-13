package com.github.uncomplexco.sidekick.application.tools.slack

import java.util.concurrent.TimeUnit
import kotlin.time.Instant

internal fun slackMessageTsToUtc(ts: String): String =
    Instant.fromEpochMilliseconds((ts.toDouble() * TimeUnit.SECONDS.toMillis(1)).toLong())
        .toString()

internal fun slackTsToUtc(ts: Int): String = Instant.fromEpochSeconds(ts.toLong()).toString()
