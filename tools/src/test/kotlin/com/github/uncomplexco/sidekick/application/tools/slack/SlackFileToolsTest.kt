package com.github.uncomplexco.sidekick.application.tools.slack

import com.github.uncomplexco.sidekick.application.session.IncomingChatFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackFileToolsTest {
    @Test
    fun `supports html markdown and text files`() {
        assertTrue(isSupportedSlackTextFile(file(mimetype = "text/html")))
        assertTrue(isSupportedSlackTextFile(file(filetype = "markdown")))
        assertTrue(isSupportedSlackTextFile(file(name = "note.txt")))

        assertFalse(isSupportedSlackTextFile(file(mimetype = "application/pdf", filetype = "pdf", name = "note.pdf")))
    }

    @Test
    fun `download filename uses slack file id and sanitized name`() {
        assertEquals("F123-my_file.md", downloadFileName(file(id = "F123", name = "my file.md")))
    }

    private fun file(
        id: String = "F123",
        name: String? = null,
        mimetype: String? = null,
        filetype: String? = null,
    ): IncomingChatFile =
        IncomingChatFile(
            id = id,
            name = name,
            title = null,
            mimetype = mimetype,
            filetype = filetype,
            urlPrivateDownload = "https://files.slack.com/files-pri/T-F/download/file",
            permalink = null,
        )
}
