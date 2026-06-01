package com.github.uncomplexco.sidekick.application

import com.slack.api.methods.MethodsClient
import org.springframework.stereotype.Component

@Component
class SharedContext {
    @Volatile
    private var currentSlackClient: MethodsClient? = null

    var slackClient: MethodsClient
        get() = currentSlackClient ?: error("Slack client is not available in SharedContext")
        set(value) {
            currentSlackClient = value
        }
}
