package com.github.uncomplexco.sidekick.adapters.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.jakarta_servlet.SlackAppServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@ConditionalOnBean(App::class)
class SlackBoltController(
    slackApp: App,
) {
    private val servlet = SlackAppServlet(slackApp)

    @PostMapping("/slack/events")
    fun handleEvents(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        servlet.service(req, resp)
    }
}
