package com.github.uncomplexco.sidekick.testing

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** HTTP edge double; the production Slack SDK still builds and sends the deletion request. */
class SlackDeletionApi : AutoCloseable {
    data class Request(val path: String, val body: String, val authorization: String?)

    val requests = CopyOnWriteArrayList<Request>()
    var responseBody = """{"ok":true,"channel":"C123ABC","ts":"1700000000.001234"}"""
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests +=
                    Request(
                        exchange.requestURI.path,
                        exchange.requestBody.bufferedReader().readText(),
                        exchange.requestHeaders.getFirst("Authorization"),
                    )
                val bytes = responseBody.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    private val slack =
        Slack.getInstance(
            SlackConfig().apply {
                isStatsEnabled = false
                methodsEndpointUrlPrefix = "http://127.0.0.1:${server.address.port}/api/"
            }
        )
    val app =
        App(
            AppConfig.builder()
                .slack(slack)
                .singleTeamBotToken("xoxb-deletion-test")
                .signingSecret("test-signing-secret")
                .build()
        )

    override fun close() {
        app.executorService().shutdownNow()
        slack.close()
        server.stop(0)
    }
}
