package com.github.uncomplexco.sidekick.application.conversation

import com.github.uncomplexco.sidekick.adapters.files.folder
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatConversationKind
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class ConversationIdentityTest {
    @Test
    fun `conversation identity contains channel and thread`() {
        val chatId = ChatConversationId("D123")
        val id = ConversationId("C123", "1700000000.000")

        assertEquals(ChatPlatform.SLACK, chatId.platform)
        assertEquals(ChatConversationKind.DIRECT_MESSAGE, chatId.kind)
        assertEquals("C123:1700000000.000", id.lockKey())
        assertEquals(id, ConversationId.fromLockKey(id.lockKey()))
        assertEquals(
            Path.of("state/slack/channels/C123/threads/1700000000.000"),
            id.folder(Path.of("state"), ChatPlatform.SLACK),
        )
    }

    @Test
    fun `direct conversation identity has no thread id`() {
        val chatId =
            ChatConversationId(
                channelId = "123456789012345678",
                platform = ChatPlatform.DISCORD,
                kind = ChatConversationKind.DIRECT_MESSAGE,
            )
        val id = ConversationId(chatId.channelId, "")

        assertEquals(false, chatId.isThread)
        assertEquals("123456789012345678:", id.lockKey())
        assertEquals(id, ConversationId.fromLockKey(id.lockKey()))
        assertEquals(
            Path.of("state/discord/channels/123456789012345678/session"),
            id.folder(Path.of("state"), ChatPlatform.DISCORD),
        )
    }
}
