package com.github.uncomplexco.sidekick.application.tools.slack

import kotlin.time.Instant

internal fun slackMessageTsToUtc(ts: String): String = Instant.fromEpochMilliseconds((ts.toDouble().times(1000)).toLong()).toString()

internal fun slackTsToUtc(ts: Int): String = Instant.fromEpochSeconds(ts.toLong()).toString()
