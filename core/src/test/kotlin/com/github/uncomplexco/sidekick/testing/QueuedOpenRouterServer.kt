package com.github.uncomplexco.sidekick.testing

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Local HTTP edge for exercising the real OpenRouter client and response parser. */
class QueuedOpenRouterServer(
    vararg responses: Response,
    path: String = "/api/v1/chat/completions",
) : AutoCloseable {
    data class Response(val body: String, val status: Int = 200)

    private val responses = ArrayDeque(responses.toList())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<String>()
    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext(path) { exchange ->
            exchange.use {
                requests += it.requestBody.bufferedReader().use { reader -> reader.readText() }
                val response = this.responses.removeFirst()
                val body = response.body.toByteArray(Charsets.UTF_8)
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(response.status, body.size.toLong())
                it.responseBody.use { output -> output.write(body) }
            }
        }
        server.start()
    }

    override fun close() = server.stop(0)
}
