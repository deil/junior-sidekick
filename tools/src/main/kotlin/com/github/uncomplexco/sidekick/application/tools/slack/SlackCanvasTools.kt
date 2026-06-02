package com.github.uncomplexco.sidekick.application.tools.slack

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.isConversationScopedChannel
import com.github.uncomplexco.sidekick.application.sessions.SessionId
import com.slack.api.methods.MethodsClient
import com.slack.api.model.canvas.CanvasDocumentContent
import org.slf4j.LoggerFactory

data class SlackCanvasRuntimeContext(
    val channelId: String?,
)

@LLMDescription("Slack canvas tools for long-form artifacts tracked in the current thread")
class SlackCanvasTools(
    private val slackClient: MethodsClient,
    private val sessionId: SessionId,
) : ToolSet {
    @Tool
    @LLMDescription(
        "Create a standalone Slack canvas for long-form output and grant the active conversation access to it. Use when the answer is better as a reusable document than a thread reply: long-form research, timelines, bios/profiles, structured notes, plans, comparisons, or anything likely to exceed one compact Slack reply. After creating it, reply with one or two short sentences plus the canvas link; do not recap the canvas contents. Do not use for short answers that fit cleanly in one normal thread reply.",
    )
    fun slackCanvasCreate(
        @LLMDescription("Canvas title.")
        title: String,
        @LLMDescription("Canvas markdown body content.")
        markdown: String,
    ): String {
        val targetChannelId = sessionId.channelId
        val normalized = normalizeCanvasMarkdown(markdown)
        val created =
            slackClient.canvasesCreate { req ->
                req.title(title)
                req.documentContent(CanvasDocumentContent.builder().markdown(normalized.markdown).build())
            }
        if (!created.isOk || created.canvasId.isNullOrBlank()) {
            throw IllegalStateException(created.error ?: "Slack canvas was created without canvas_id")
        }

        val canvasId = created.canvasId
        grantConversationCanvasAccess(canvasId, targetChannelId)
        val permalink = fetchCanvasPermalink(canvasId)
        log.debug(
            "Created standalone Slack canvas session={} canvasId={} channelId={}",
            sessionId.lockKey(),
            canvasId,
            targetChannelId,
        )
        val result = """{"ok":true,"canvas_id":"$canvasId","permalink":${
            permalink?.let {
                "\"$it\""
            } ?: "null"
        },"summary":"Created canvas $canvasId"}"""
        return result
    }

    private fun grantConversationCanvasAccess(
        canvasId: String,
        channelId: String,
    ) {
        if (!isConversationScopedChannel(channelId)) {
            return
        }

        runCatching {
            slackClient.canvasesAccessSet { req ->
                req.canvasId(canvasId)
                req.accessLevel("write")
                req.channelIds(listOf(channelId))
            }
        }.onFailure { error ->
            log.warn(
                "Failed to grant Slack canvas access session={} canvasId={} channelId={}",
                sessionId.lockKey(),
                canvasId,
                channelId,
                error,
            )
        }
    }

    private fun fetchCanvasPermalink(canvasId: String): String? =
        runCatching {
            val info = slackClient.filesInfo { req -> req.file(canvasId) }
            if (info.isOk) {
                info.file?.permalink
            } else {
                null
            }
        }.getOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(SlackCanvasTools::class.java)
    }
}

fun normalizeCanvasMarkdown(markdown: String): CanvasMarkdownNormalization {
    var normalizedHeadingCount = 0
    val normalized =
        markdown
            .lines()
            .joinToString("\n") { line ->
                line.replace(Regex("^(#{4,})(?=\\s)")) {
                    normalizedHeadingCount += 1
                    "###"
                }
            }
    return CanvasMarkdownNormalization(normalized, normalizedHeadingCount)
}

data class CanvasMarkdownNormalization(
    val markdown: String,
    val normalizedHeadingCount: Int,
)

/*
private fun mergeRecentCanvases(
    existing: List<CanvasArtifactSummary>,
    canvasId: String,
    title: String,
    url: String?,
): List<CanvasArtifactSummary> {
    val next =
        CanvasArtifactSummary(
            id = canvasId,
            title = title,
            url = url,
            createdAt =
                Instant
                    .now()
                    .toString(),
        )
    return (listOf(next) + existing.filter { it.id != canvasId }).take(5)
}
*/
