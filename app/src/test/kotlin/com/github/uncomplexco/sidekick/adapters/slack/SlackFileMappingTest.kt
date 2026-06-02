package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.model.Attachment
import com.slack.api.model.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SlackFileMappingTest {
    @Test
    fun `uses directly attached files when present`() {
        val files =
            incomingChatFiles(
                files = listOf(slackFile("F_DIRECT")),
                attachments = listOf(Attachment.builder().files(listOf(slackFile("F_FORWARDED"))).build()),
            )

        assertEquals(listOf("F_DIRECT"), files.map { it.id })
    }

    @Test
    fun `falls back to files from attachments`() {
        val files =
            incomingChatFiles(
                files = emptyList(),
                attachments = listOf(Attachment.builder().files(listOf(slackFile("F_FORWARDED"))).build()),
            )

        assertEquals(listOf("F_FORWARDED"), files.map { it.id })
    }

    private fun slackFile(id: String): File =
        File
            .builder()
            .id(id)
            .name("$id.md")
            .mimetype("text/markdown")
            .filetype("markdown")
            .urlPrivateDownload("https://files.slack.com/$id")
            .build()
}
