package com.github.uncomplexco.sidekick.application.tools.web

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

const val MAX_WEB_FETCH_RESPONSE_BYTES = 5 * 1024 * 1024
const val DEFAULT_WEB_FETCH_TIMEOUT_SECONDS = 30
const val MAX_WEB_FETCH_TIMEOUT_SECONDS = 120

private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

@LLMDescription("Web tools for fetching HTTP and HTTPS content")
class WebFetchTools(
    private val agentName: String,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) : ToolSet {
    @Tool
    @LLMDescription(
        "Fetch content from an HTTP or HTTPS URL and return it as text, markdown, or HTML. Markdown is the default. Use a more targeted tool when one is available. This tool is read-only.",
    )
    fun webFetch(
        @LLMDescription("The HTTP or HTTPS URL to fetch content from.")
        url: String,
        @LLMDescription("The format to return the content in. Must be text, markdown, or html. Defaults to markdown.")
        format: String? = null,
        @LLMDescription("Optional request timeout in seconds.")
        timeout: Int? = null,
    ): WebFetchResult {
        val normalizedFormat = normalizeWebFetchFormat(format)
        val normalizedTimeout = normalizeWebFetchTimeout(timeout)
        val uri = normalizeHttpUri(url)
        val firstResponse = execute(uri, normalizedFormat, normalizedTimeout, BROWSER_USER_AGENT)
        val response =
            if (firstResponse.statusCode() == 403 && firstResponse.headers().firstValue("cf-mitigated").orElse(null) == "challenge") {
                execute(uri, normalizedFormat, normalizedTimeout, agentName)
            } else {
                firstResponse
            }
        if (response.statusCode() !in 200..299) {
            throw ToolException.ValidationFailure("Unable to fetch $url")
        }

        val contentType = response.headers().firstValue("content-type").orElse("")
        val mime = contentType.substringBefore(";").trim().lowercase()
        if (isImageAttachment(mime)) {
            throw ToolException.ValidationFailure("Unsupported fetched image content type: $mime")
        }
        if (!isTextualMime(mime)) {
            throw ToolException.ValidationFailure("Unsupported fetched file content type: $mime")
        }

        val body = response.body().readCapped(response.headers().firstValueAsLong("content-length").orElse(-1))
        val content = convertWebFetchContent(body.decodeToString(), contentType, normalizedFormat)
        return WebFetchResult(
            ok = true,
            url = uri.toString(),
            contentType = contentType,
            format = normalizedFormat,
            output = content,
        )
    }

    private fun execute(
        uri: URI,
        format: String,
        timeout: Int,
        userAgent: String,
    ): HttpResponse<InputStream> {
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(Duration.ofSeconds(timeout.toLong()))
                .header("User-Agent", userAgent)
                .header("Accept", acceptHeader(format))
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }
}

fun normalizeWebFetchFormat(format: String?): String {
    val value = format?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "markdown"
    if (value !in setOf("text", "markdown", "html")) {
        throw ToolException.ValidationFailure("Web fetch format must be text, markdown, or html.")
    }
    return value
}

fun normalizeWebFetchTimeout(timeout: Int?): Int {
    val value = timeout ?: DEFAULT_WEB_FETCH_TIMEOUT_SECONDS
    if (value < 1) {
        throw ToolException.ValidationFailure("Web fetch timeout must be greater than or equal to 1.")
    }
    return minOf(value, MAX_WEB_FETCH_TIMEOUT_SECONDS)
}

fun normalizeHttpUri(url: String): URI {
    val uri = URI(url.trim())
    if (uri.scheme != "http" && uri.scheme != "https") {
        throw ToolException.ValidationFailure("URL must use http:// or https://")
    }
    return uri
}

fun convertWebFetchContent(
    content: String,
    contentType: String,
    format: String,
): String {
    if (!contentType.contains("text/html", ignoreCase = true)) {
        return content
    }
    return when (format) {
        "markdown" -> convertHtmlToMarkdown(content)
        "text" -> extractTextFromHtml(content)
        else -> content
    }
}

fun extractTextFromHtml(html: String): String =
    Jsoup.parse(html).text().trim()

fun convertHtmlToMarkdown(html: String): String =
    FlexmarkHtmlConverter
        .builder()
        .build()
        .convert(html)
        .trim()

private fun InputStream.readCapped(contentLength: Long): ByteArray {
    if (contentLength > MAX_WEB_FETCH_RESPONSE_BYTES) {
        throw ToolException.ValidationFailure("Response too large (exceeds $MAX_WEB_FETCH_RESPONSE_BYTES byte limit)")
    }
    use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                return output.toByteArray()
            }
            total += read
            if (total > MAX_WEB_FETCH_RESPONSE_BYTES) {
                throw ToolException.ValidationFailure("Response too large (exceeds $MAX_WEB_FETCH_RESPONSE_BYTES byte limit)")
            }
            output.write(buffer, 0, read)
        }
    }
}

private fun acceptHeader(format: String): String =
    when (format) {
        "markdown" -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1"
        "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1"
        "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1"
        else -> error("Unexpected web fetch format: $format")
    }

private fun isImageAttachment(mime: String): Boolean =
    mime.startsWith("image/") && mime != "image/svg+xml" && mime != "image/vnd.fastbidsheet"

private fun isTextualMime(mime: String): Boolean =
    mime.isBlank() ||
        mime.startsWith("text/") ||
        mime == "application/json" ||
        mime.endsWith("+json") ||
        mime == "application/xml" ||
        mime.endsWith("+xml") ||
        mime == "application/javascript" ||
        mime == "application/x-javascript"

@Serializable
data class WebFetchResult(
    val ok: Boolean,
    val url: String,
    val contentType: String,
    val format: String,
    val output: String,
)
