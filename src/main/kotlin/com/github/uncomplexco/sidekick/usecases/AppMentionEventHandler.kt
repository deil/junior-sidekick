package com.github.uncomplexco.sidekick.usecases

import com.github.uncomplexco.sidekick.application.conversations.Message
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AppMentionEventHandler {
    fun handle(
        channel: String,
        threadId: String?,
        messageId: String,
        sender: String,
        text: String,
        historyLoader: () -> List<Message>,
    ) {
        val history = historyLoader()
        log.debug("[#$channel] @$sender: $text")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppMentionEventHandler::class.java)
    }
}
