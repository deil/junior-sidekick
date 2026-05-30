package com.github.uncomplexco.sidekick.usecases

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class AppMentionEventHandler {
    fun handle(
        channel: String,
        sender: String,
        text: String,
    ) {
        log.debug("[#$channel] @$sender: $text")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AppMentionEventHandler::class.java)
    }
}
