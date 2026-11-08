package com.github.uncomplexco.sidekick.adapters.chat

import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

private val SPEC_FENCE_RE = Regex("(?m)^ {0,3}(?:`{3,}|~{3,})spec[ \\t]*\\r?$")

data class ChatUiPart(
    val text: String? = null,
    val payload: JsonObject? = null,
    val fallbackText: String = "",
)

@Component
class ChatUiRenderer(
    @Value("\${agent.chat-ui.node:node}") private val node: String = "node",
    @Value("\${agent.chat-ui.bridge-directory:./js-bridge}")
    private val bridgeDirectory: String = "./js-bridge",
    @Value("\${agent.chat-ui.enabled:false}") private val enabled: Boolean = false,
) {
    suspend fun render(platform: String, text: String): List<ChatUiPart> {
        if (!enabled || !SPEC_FENCE_RE.containsMatchIn(text)) return listOf(ChatUiPart(text = text))

        return coroutineScope {
            val root = Path.of(bridgeDirectory).toAbsolutePath().normalize()
            log.info("Rendering completed {} reply through json-render", platform)
            val builder =
                ProcessBuilder(node, "chat-ui/render-reply.mjs")
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
            builder.environment().clear()
            val process = builder.start()
            try {
                val exchange =
                    async(Dispatchers.IO) {
                        process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                            it.write(
                                buildJsonObject {
                                        put("platform", platform)
                                        put("text", text)
                                    }
                                    .toString()
                            )
                        }
                        val output =
                            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        check(process.waitFor() == 0) {
                            "Chat UI renderer failed; see stderr for details."
                        }
                        Json.parseToJsonElement(output).jsonArray.map { entry ->
                            val part = entry.jsonObject
                            ChatUiPart(
                                text = part["text"]?.jsonPrimitive?.content,
                                payload = part["payload"]?.jsonObject,
                                fallbackText =
                                    part["fallback_text"]?.jsonPrimitive?.content.orEmpty(),
                            )
                        }
                    }
                withTimeoutOrNull(30_000) { exchange.await() }
                    ?: throw IOException("Chat UI rendering exceeded 30 seconds.")
            } finally {
                process.destroyForcibly()
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ChatUiRenderer::class.java)
    }
}
