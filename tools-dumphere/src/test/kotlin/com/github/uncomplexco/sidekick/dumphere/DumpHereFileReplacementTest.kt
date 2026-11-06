package com.github.uncomplexco.sidekick.dumphere

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

private val REPLACEMENT_RESPONSE =
    """
    {
      "id": "page1",
      "title": "Report",
      "url": "https://files.internal/p/page1",
      "contentFormat": "HTML",
      "createdAt": "2026-11-01T12:00:00Z",
      "createdBy": {"email": "author@example.com", "name": "Original Author"},
      "updatedAt": "2026-11-06T12:00:00Z",
      "updatedBy": {"email": "editor@example.com", "name": "Editor"},
      "version": 2,
      "bytes": 1024,
      "live": false,
      "visibility": {"publicUntil": null, "visibility": "internal"},
      "customMetadata": {}
    }
    """
        .trimIndent()
        .toByteArray()

class DumpHereFileReplacementTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `replaces existing artifact with raw workspace file and returns its share URL`() {
        val content = "<html><body>Updated report: café</body></html>"
        val file = directory.resolve("report.html")
        Files.writeString(file, content)
        var requestMethod: String? = null
        var requestPath: String? = null
        var authorization: String? = null
        var requestBody: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestMethod = exchange.requestMethod
            requestPath = exchange.requestURI.toString()
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, REPLACEMENT_RESPONSE.size.toLong())
            exchange.responseBody.use { it.write(REPLACEMENT_RESPONSE) }
        }
        server.start()
        val publisher =
            DumpHereFilePublisher("http://127.0.0.1:${server.address.port}/", "user", "pass")
        val tools = InternalFileExchangeTools(publisher) { file }

        try {
            val result = tools.replaceInternalFile("page1", "/data/session/report.html")

            assertEquals(
                InternalFileReplaceResult(
                    "page1",
                    "Report",
                    "https://files.internal/p/page1",
                    2,
                    1024,
                    "HTML",
                    "2026-11-06T12:00:00Z",
                    false,
                ),
                result,
            )
            assertEquals("POST", requestMethod)
            assertEquals("/api/pages/page1/replace", requestPath)
            assertEquals("Basic dXNlcjpwYXNz", authorization)
            assertEquals(content, requestBody)
        } finally {
            server.stop(0)
        }
    }
}
