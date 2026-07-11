package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionFileRef
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals

class AttachmentsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `renders only non-blank file summaries`() {
        val rendered =
            renderFileAttachments(
                conversationId = ConversationId("C123", "thread"),
                files = listOf(file("F1", "Visible title: A & B"), file("F2", "  ")),
                basePath = dir,
                maxChars = 100,
            )

        assertContains(rendered, "summary: Visible title: A &amp; B")
        assertEquals(1, "summary:".toRegex().findAll(rendered).count())
    }

    private fun file(
        id: String,
        summary: String,
    ) = SessionFileRef(
        id = id,
        name = "$id.png",
        mimetype = "image/png",
        filetype = "png",
        urlPrivateDownload = "https://example.com/$id",
        localPath = "/data/session/$id.png",
        summary = summary,
    )
}
