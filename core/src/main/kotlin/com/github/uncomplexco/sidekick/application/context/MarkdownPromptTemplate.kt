package com.github.uncomplexco.sidekick.application.context

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.PebbleException
import io.pebbletemplates.pebble.loader.StringLoader
import java.io.StringWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class MarkdownPromptTemplate(
    @Value("\${agent.chat-ui.enabled:false}") private val chatUiEnabled: Boolean = false
) {
    private val engine =
        PebbleEngine.Builder()
            .loader(StringLoader())
            .autoEscaping(false)
            .strictVariables(true)
            .cacheActive(false)
            .build()

    fun render(
        markdown: String,
        source: String,
        variables: Map<String, String> = emptyMap(),
    ): String {
        val context = variables + mapOf("features" to mapOf("chat_ui" to chatUiEnabled))
        return try {
            val output = StringWriter()
            engine.getTemplate(markdown).evaluate(output, context)
            output.toString()
        } catch (error: PebbleException) {
            throw IllegalArgumentException(
                "Invalid prompt template in $source: ${error.message}",
                error,
            )
        }
    }
}
