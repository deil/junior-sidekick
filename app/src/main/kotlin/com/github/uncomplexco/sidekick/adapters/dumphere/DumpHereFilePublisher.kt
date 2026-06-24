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

    override fun publishContent(
        content: String,
        title: String,
        mimeType: String,
    ): FilePublisher.Result {
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

    override fun publishFile(
        path: String,
        title: String,
        mimeType: String,
    ): FilePublisher.Result {
        val content =
            runCatching { Files.readString(Path.of(path)) }
                .getOrElse { return FilePublisher.Result.Error("Cannot read file: ${it.message}") }

        return publishContent(content, title, mimeType)
    }

    override fun readFileContents(
        id: String,
        offset: Int?,
        limit: Int?,
    ): String {
        var url = "${baseUrl.trimEnd('/')}/api/agent/pages/$id/contents"
        val params = listOfNotNull(offset?.let { "offset=$it" }, limit?.let { "limit=$it" })
        if (params.isNotEmpty()) url += "?${params.joinToString("&")}"

        return restClient
            .get()
            .uri(url)
            .headers { it.setBasicAuth(username, password) }
            .retrieve()
            .body(String::class.java)
            ?: error("DumpHere read returned an empty response")
    }

    override fun editFileContents(
        id: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): String =
        restClient
            .post()
            .uri("${baseUrl.trimEnd('/')}/api/agent/pages/{id}/edit", id)
            .headers { it.setBasicAuth(username, password) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(EditFileContentsRequest(oldString, newString, replaceAll))
            .retrieve()
            .body(String::class.java)
            ?: error("DumpHere edit returned an empty response")
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

private data class EditFileContentsRequest(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean,
)
