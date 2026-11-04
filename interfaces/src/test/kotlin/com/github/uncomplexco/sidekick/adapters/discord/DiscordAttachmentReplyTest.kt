package com.github.uncomplexco.sidekick.adapters.discord

import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.turn.ReplyAttachment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscordAttachmentReplyTest {
    @TempDir lateinit var dir: Path
    private val scope = SidekickCoroutineScope()

    @AfterEach fun closeScope() = scope.close()

    @Test
    fun `reply uploads generated attachments and sends text`() = runBlocking {
        val attachment = attachment()
        val sent = mutableListOf<String>()
        val uploaded = mutableListOf<ReplyAttachment>()
        val handler =
            DiscordTurnOutput(
                scope = scope,
                upload = {
                    uploaded += it
                    DiscordSentMessage("file-reply", 2)
                },
            ) {
                sent += it
                DiscordSentMessage("reply", 3)
            }

        val result = handler.deliver(AgentResponse("Ready.", listOf(attachment)))

        assertEquals(listOf("Ready."), sent)
        assertEquals(listOf(attachment), uploaded)
        assertEquals("reply", result.messageId)
        assertEquals(3, result.timestamp)
    }

    @Test
    fun `attachment-only reply returns uploaded message metadata`() = runBlocking {
        val attachment = attachment()
        val uploaded = mutableListOf<ReplyAttachment>()
        val handler =
            DiscordTurnOutput(
                scope = scope,
                upload = {
                    uploaded += it
                    DiscordSentMessage("file-reply", 42)
                },
            ) {
                error("No text should be sent")
            }

        val result = handler.deliver(AgentResponse("", listOf(attachment)))

        assertEquals(listOf(attachment), uploaded)
        assertEquals("file-reply", result.messageId)
        assertEquals(42, result.timestamp)
    }

    @Test
    fun `no-reply marker does not suppress attachments`() = runBlocking {
        val attachment = attachment()
        val uploaded = mutableListOf<ReplyAttachment>()
        val handler =
            DiscordTurnOutput(
                scope = scope,
                upload = {
                    uploaded += it
                    DiscordSentMessage("file-reply", 42)
                },
            ) {
                error("No text should be sent")
            }

        val result =
            handler.deliver(AgentResponse(SystemPromptBuilder.NO_REPLY_MARKER, listOf(attachment)))

        assertEquals(listOf(attachment), uploaded)
        assertEquals("file-reply", result.messageId)
    }

    @Test
    fun `failed attachment upload does not prevent text reply`() = runBlocking {
        val attachment = attachment()
        val sent = mutableListOf<String>()
        val handler =
            DiscordTurnOutput(scope, upload = { throw IOException("Upload failed") }) {
                sent += it
                DiscordSentMessage("reply", 42)
            }

        val result = handler.deliver(AgentResponse("Ready.", listOf(attachment)))

        assertEquals(listOf("Ready."), sent)
        assertEquals("reply", result.messageId)
    }

    private fun attachment(): ReplyAttachment {
        val path = dir.resolve("report.csv")
        Files.writeString(path, "report")
        return ReplyAttachment(path, "report.csv", "text/csv", 6)
    }
}
