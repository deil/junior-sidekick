package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.conversations.Message
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class PrivateMessageEventHandler {
    fun handle(
        threadId: String?,
        messageId: String,
        sender: String,
        text: String,
        historyLoader: () -> List<Message>,
    ) {
        if (threadId != null) {
            log.debug("[DM/$threadId] @$sender: $text")
        } else {
            log.debug("[DM] @$sender: $text")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PrivateMessageEventHandler::class.java)
    }
}
