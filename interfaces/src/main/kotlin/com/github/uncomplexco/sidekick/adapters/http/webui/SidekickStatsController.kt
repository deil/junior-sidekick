package com.github.uncomplexco.sidekick.adapters.http.webui

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.uncomplexco.sidekick.application.stats.WeeklyStatsService
import java.time.Clock
import java.time.Duration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class WebUsageStats(
    @get:JsonProperty("period_days") val periodDays: Int,
    val users: Int,
    val conversations: Int,
    val channels: Int,
    @get:JsonProperty("tokens_consumed") val tokensConsumed: Long,
)

@RestController
@RequestMapping("/api/web/user")
class SidekickStatsController(
    private val stats: WeeklyStatsService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping("/stats")
    fun statistics(@RequestParam(defaultValue = "$WEEK") days: Int): WebUsageStats {
        if (days != WEEK && days != MONTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be $WEEK or $MONTH")
        }

        val gathered = stats.gather(clock.instant(), Duration.ofDays(days.toLong()))
        return WebUsageStats(
            periodDays = days,
            users = gathered.users,
            conversations = gathered.conversations,
            channels = gathered.channels,
            tokensConsumed = gathered.tokensConsumed,
        )
    }
}

private const val WEEK = 7
private const val MONTH = 30
