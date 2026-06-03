package com.github.uncomplexco.sidekick.adapters.dumphere

import com.github.uncomplexco.sidekick.application.tools.integrations.FilePublisher
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.nio.file.Files
import java.nio.file.Path

@Component
class DumpHereFilePublisher(
    @Value($$"${integrations.dumphere.base-url}") private val baseUrl: String,
    @Value($$"${integrations.dumphere.username}") private val username: String,
    @Value($$"${integrations.dumphere.password}") private val password: String,
) : FilePublisher {
    private val restClient = RestClient.create()

    override fun publishFile(
        path: String,
        title: String,
        mimeType: String,
    ): FilePublisher.Result {
        val content =
            runCatching { Files.readString(Path.of(path)) }
                .getOrElse { return FilePublisher.Result.Error("Cannot read file: ${it.message}") }

        val request = PublishFileRequest(title, content, mimeType)
        val published =
            runCatching {
                restClient
                    .post()
                    .uri("${baseUrl.trimEnd('/')}/api/pages")
                    .headers { it.setBasicAuth(username, password) }
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PublishFileResponse::class.java)
            }.getOrElse {
                return FilePublisher.Result.Error("DumpHere publish failed: ${it.message}")
            } ?: return FilePublisher.Result.Error("DumpHere publish returned an empty response")

        return FilePublisher.Result.Ok(published.url)
    }
}

private data class PublishFileRequest(
    val title: String,
    val content: String,
    val mimeType: String,
)

private data class PublishFileResponse(
    val url: String,
    val version: Int,
)
