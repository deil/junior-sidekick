package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.conversations.Message
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ChannelMessageEventHandler {
    fun handle(
        channel: String,
        threadId: String?,
        messageId: String,
        sender: String,
        text: String,
        historyLoader: () -> List<Message>,
    ) {
        if (threadId != null) {
            log.debug("[#$channel/$threadId] @$sender: $text")
        } else {
            log.debug("[#$channel] @$sender: $text")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChannelMessageEventHandler::class.java)
    }
}
