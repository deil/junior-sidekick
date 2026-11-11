package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.github.uncomplexco.sidekick.testing.CatalogDiscordChat
import com.github.uncomplexco.sidekick.testing.ToolCatalogFixture
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ToolArgumentNamesTest {
    @TempDir lateinit var dir: Path

    @ParameterizedTest(name = "{0} exposes {1} instead of {2}")
    @CsvSource(
        "scheduled_jobs__update, job_id, jobId",
        "scheduled_jobs__delete, job_id, jobId",
        "slackChannelHistory, scan_depth, scanDepth",
        "slackChannelHistory, bounds_inclusive, boundsInclusive",
        "slackThreadHistory, thread_ts, threadTs",
        "slackThreadHistory, scan_depth, scanDepth",
        "slackThreadHistory, bounds_inclusive, boundsInclusive",
        "slackUserSearch, scan_depth, scanDepth",
        "Task, subagent_type, subagentType",
    )
    fun `registered Slack tools preserve argument schema names`(
        toolName: String,
        exposedName: String,
        kotlinName: String,
    ) = runBlocking {
        val fixture = ToolCatalogFixture(dir)

        val registry = fixture.catalog.bindOrchestrator(fixture.execution())

        val descriptor = registry.getTool(toolName).descriptor
        val names = (descriptor.requiredParameters + descriptor.optionalParameters).map { it.name }
        assertContains(names, exposedName)
        assertFalse(kotlinName in names)
    }

    @Test
    fun `Discord tool accepts snake case thread argument and serializes snake case result`() =
        runBlocking {
            val fixture = ToolCatalogFixture(dir)
            val registry =
                fixture.catalog.bindOrchestrator(fixture.execution(chat = CatalogDiscordChat()))
            @Suppress("UNCHECKED_CAST")
            val tool = registry.getTool("discordThreadHistory") as Tool<Any?, Any?>
            val serializer = KotlinxSerializer()
            val raw = JSONObject(mapOf("thread_id" to JSONPrimitive("thread")))

            val decoded = tool.decodeArgs(raw, serializer)
            val result = tool.execute(decoded)
            val encoded =
                serializer.encodeJSONElementToString(tool.encodeResult(result, serializer))

            assertEquals(raw, tool.encodeArgs(decoded, serializer))
            val json = Json.parseToJsonElement(encoded).jsonObject
            assertEquals("thread", json["thread_id"]?.jsonPrimitive?.content)
            assertEquals("discord-channel", json["channel_id"]?.jsonPrimitive?.content)
            assertEquals(
                setOf("channel_id", "thread_id", "count", "messages", "next_cursor"),
                json.keys,
            )
        }
}
