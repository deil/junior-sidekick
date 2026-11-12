package com.github.uncomplexco.sidekick.application.tools

import com.github.uncomplexco.sidekick.application.core.scheduledTrigger
import com.github.uncomplexco.sidekick.application.tools.automations.AutomationListResult
import com.github.uncomplexco.sidekick.application.tools.automations.AutomationView
import com.github.uncomplexco.sidekick.application.tools.bash.BashResult
import com.github.uncomplexco.sidekick.application.tools.discord.DiscordChannelHistoryResult
import com.github.uncomplexco.sidekick.application.tools.discord.DiscordHistoryMessage
import com.github.uncomplexco.sidekick.application.tools.discord.DiscordThreadHistoryResult
import com.github.uncomplexco.sidekick.application.tools.files.AttachFileResult
import com.github.uncomplexco.sidekick.application.tools.slack.SlackChannelHistoryResult
import com.github.uncomplexco.sidekick.application.tools.slack.SlackMessage
import com.github.uncomplexco.sidekick.application.tools.slack.SlackThreadHistoryResult
import com.github.uncomplexco.sidekick.application.tools.stats.WeeklyStatsResult
import com.github.uncomplexco.sidekick.application.tools.subagents.TaskTool
import com.github.uncomplexco.sidekick.application.tools.system.SystemTimeResult
import com.github.uncomplexco.sidekick.application.tools.system.TimestampToIsoUtcResult
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ToolSerializationNamesTest {
    @ParameterizedTest(name = "{0} preserves serialized field names")
    @MethodSource("chatResponses", "otherContracts")
    fun `camel case properties preserve serialized contract`(
        contract: String,
        serialized: String,
        expectedKeys: String,
    ) {
        val json = Json.parseToJsonElement(serialized).jsonObject

        assertEquals(expectedKeys.split(',').toSet(), json.keys, contract)
    }

    companion object {
        @JvmStatic
        fun chatResponses(): List<Arguments> =
            listOf(
                Arguments.of(
                    "Discord channel history",
                    Json.encodeToString(
                        DiscordChannelHistoryResult("channel", 0, emptyList(), null)
                    ),
                    "channel_id,count,messages,next_cursor",
                ),
                Arguments.of(
                    "Discord thread history",
                    Json.encodeToString(
                        DiscordThreadHistoryResult("channel", "thread", 0, emptyList(), null)
                    ),
                    "channel_id,thread_id,count,messages,next_cursor",
                ),
                Arguments.of(
                    "Discord message",
                    Json.encodeToString(
                        DiscordHistoryMessage("id", "now", "user", "name", false, "text", null)
                    ),
                    "id,sent_at,user_id,username,is_bot,text,thread_id",
                ),
                Arguments.of(
                    "Slack channel history",
                    Json.encodeToString(SlackChannelHistoryResult("channel", 0, emptyList(), null)),
                    "channel_id,count,messages,next_cursor",
                ),
                Arguments.of(
                    "Slack thread history",
                    Json.encodeToString(
                        SlackThreadHistoryResult("channel", "thread", 0, emptyList(), null)
                    ),
                    "channel_id,thread_ts,count,messages,next_cursor",
                ),
                Arguments.of(
                    "Slack message",
                    Json.encodeToString(
                        SlackMessage(
                            "id",
                            "now",
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            0,
                            null,
                            null,
                        )
                    ),
                    "id,sent_at,user,username,bot_id,subtype,text,is_thread,reply_count,thread_ts,parent_user_id",
                ),
            )

        @JvmStatic
        fun otherContracts(): List<Arguments> =
            listOf(
                Arguments.of(
                    "Bash result",
                    Json.encodeToString(BashResult(true, 0, false, false, "ok", "/")),
                    "ok,exit_code,timed_out,output_truncated,output,workdir",
                ),
                Arguments.of(
                    "Attachment result",
                    Json.encodeToString(AttachFileResult("name", "text/plain", 1)),
                    "filename,mime_type,bytes",
                ),
                Arguments.of(
                    "Automation list",
                    Json.encodeToString(AutomationListResult(emptyList(), 0, 1)),
                    "automations,active_count,active_limit",
                ),
                Arguments.of(
                    "Automation",
                    Json.encodeToString(
                        AutomationView(
                            1L,
                            1,
                            "name",
                            null,
                            "instructions",
                            scheduledTrigger("0 9 * * *", "UTC"),
                            true,
                            null,
                            "2026-11-11T09:00:00Z",
                            "2026-11-11T09:00:00Z",
                        )
                    ),
                    "id,uid,name,description,instructions,trigger,enabled,last_run_at,created_at,updated_at",
                ),
                Arguments.of(
                    "Weekly stats",
                    Json.encodeToString(WeeklyStatsResult(1, 1, 1, 1)),
                    "channels,conversations,tokens_consumed,users",
                ),
                Arguments.of(
                    "Task arguments",
                    Json.encodeToString(TaskTool.Args("description", "prompt", "explore")),
                    "description,prompt,subagent_type",
                ),
                Arguments.of(
                    "System time",
                    Json.encodeToString(SystemTimeResult(true, 1, "now")),
                    "ok,unix_ms,iso_utc",
                ),
                Arguments.of(
                    "Timestamp conversion",
                    Json.encodeToString(TimestampToIsoUtcResult(true, 1, "seconds", "now")),
                    "ok,timestamp,interpreted_as,iso_utc",
                ),
            )
    }
}
