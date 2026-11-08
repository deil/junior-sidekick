package com.github.uncomplexco.sidekick.adapters.chat

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

private const val SPEC_REPLY =
    """Here are the results.
```spec
{"op":"add","path":"/root","value":"card"}
```
"""

class ChatUiRendererTest {
    @ParameterizedTest
    @ValueSource(strings = ["slack", "discord"])
    fun `disabled by default leaves spec replies unchanged without requiring Node`(
        platform: String
    ) = runTest {
        val renderer = ChatUiRenderer(node = "/nonexistent/chat-ui-node")

        val parts = renderer.render(platform, SPEC_REPLY)

        assertEquals(listOf(ChatUiPart(text = SPEC_REPLY)), parts)
    }

    @Test
    fun `enabled rendering starts the bridge for spec replies`() = runTest {
        val renderer = ChatUiRenderer(node = "/nonexistent/chat-ui-node", enabled = true)

        assertFailsWith<IOException> { renderer.render("slack", SPEC_REPLY) }
    }

    @Test
    fun `enabled rendering leaves ordinary replies unchanged without requiring Node`() = runTest {
        val renderer = ChatUiRenderer(node = "/nonexistent/chat-ui-node", enabled = true)

        val parts = renderer.render("discord", "An ordinary reply.")

        assertEquals(listOf(ChatUiPart(text = "An ordinary reply.")), parts)
    }
}
