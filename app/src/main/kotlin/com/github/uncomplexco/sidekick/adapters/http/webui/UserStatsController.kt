package com.github.uncomplexco.sidekick.adapters.http.webui

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
    val period_days: Int,
    val users: Int,
    val conversations: Int,
    val channels: Int,
    val tokens_consumed: Long,
)

@RestController
@RequestMapping("/api/web/user")
class UserStatsController(
    private val stats: WeeklyStatsService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @GetMapping("/stats")
    fun statistics(@RequestParam(defaultValue = "7") days: Int): WebUsageStats {
        if (days != 7 && days != 30) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7 or 30")
        }

        val gathered = stats.gather(clock.instant(), Duration.ofDays(days.toLong()))
        return WebUsageStats(
            period_days = days,
            users = gathered.users,
            conversations = gathered.conversations,
            channels = gathered.channels,
            tokens_consumed = gathered.tokensConsumed,
        )
    }
}
