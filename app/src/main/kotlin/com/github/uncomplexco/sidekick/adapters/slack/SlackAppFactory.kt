package com.github.uncomplexco.sidekick.adapters.slack

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatConversationId
import com.github.uncomplexco.sidekick.application.chat.ChatMessageType
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.InboundMessage
import com.github.uncomplexco.sidekick.application.runtime.SharedContext
import com.github.uncomplexco.sidekick.usecases.HandleIncomingChatMessageUsecase
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.middleware.builtin.Assistant
import com.slack.api.methods.response.views.ViewsPublishResponse
import com.slack.api.model.block.HeaderBlock
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.block.SectionBlock
import com.slack.api.model.event.AppHomeOpenedEvent
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageChangedEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.model.event.MessageFileShareEvent
import com.slack.api.model.view.View
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files

@Configuration
@ConditionalOnExpression(
    $$"'${adapters.slack.bot.token:}' != '' and '${adapters.slack.bot.signing-secret:}' != ''",
)
class SlackAppFactory {
    @Bean
    fun slackApp(
        agentConfig: AgentConfig,
        slackAdapterConfig: AppConfig,
        sharedContext: SharedContext,
        eventDeduper: HandledEventsDeduper,
        handleIncomingChatMessage: HandleIncomingChatMessageUsecase,
        slackFileIngestor: SlackFileIngestor,
    ): App {
        val app = App(slackAdapterConfig)
        sharedContext.slackClient = app.client()

        app.assistant(buildSlackAssistant(app, eventDeduper, handleIncomingChatMessage, slackFileIngestor))

        app.event(MessageChangedEvent::class.java) { payload, ctx ->
            ctx.ack()
        }

        app.event(AppHomeOpenedEvent::class.java) { payload, ctx ->
            val ack = ctx.ack()

            if (payload.event.tab.equals("home", ignoreCase = true)) {
                async(app) {
                    val view = appHomeView(agentConfig)
                    val response =
                        ctx.client().viewsPublish { req ->
                            req.userId(payload.event.user)
                            req.view(view)
                        }

                    if (!response.isOk) {
                        logAppHomePublishFailure(response, payload.event.user, view)
                    }
                }
            }

            ack
        }

        app.event(AppMentionEvent::class.java) { payload, ctx ->
            val event = payload.event
            log.debug("AppMentionEvent in ${event.channel}")
            if (!event.text.isNullOrBlank() && eventDeduper.put(event.channel, event.ts)) {
                async(app) {
                    val conversationId = event.toConversationId()
                    val responseThreadTs = event.threadTs ?: event.ts
                    handleIncomingChatMessage.handle(
                        conversationId,
                        InboundMessage(
                            id = event.ts,
                            createdAtMs = slackTsToMillis(event.ts),
                            sender = toMessageAuthor(event.user, ctx),
                            text = event.text!!.trim(),
                            type = ChatMessageType.EXPLICIT_MENTION,
                            files = incomingChatFiles(event.files, event.attachments),
                        ),
                        ChatPlatformAdapter(
                            botUsername = ctx.botUserId,
                            historyLoader = { sessionId ->
                                if (event.threadTs != null) {
                                    loadThreadHistory(ctx, event.threadTs, event.ts, sessionId, slackFileIngestor)
                                } else {
                                    emptyList()
                                }
                            },
                            reply = replyInSlack(ctx, responseThreadTs),
                            activity = slackActivityIndicator(ctx, responseThreadTs),
                            fileIngestor = slackFileIngestor::ingest,
                        ),
                    )
                }
            }

            ctx.ack()
        }

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            if (!event.text.isNullOrBlank() && !isBotsOwnMessage(event.botId, ctx) &&
                !containsMention(
                    event.text,
                    ctx.botUserId,
                ) &&
                eventDeduper.put(event.channel, event.ts)
            ) {
                if (event.channelType != "im") {
                    async(app) {
                        val responseThreadTs = event.threadTs ?: event.ts
                        handleIncomingChatMessage.handle(
                            event.toConversationId(),
                            InboundMessage(
                                id = event.ts,
                                createdAtMs = slackTsToMillis(event.ts),
                                sender = toMessageAuthor(event.user, ctx),
                                text = event.text.trim(),
                                type = ChatMessageType.PASSIVE_MESSAGE,
                                files = incomingChatFiles(event.files, event.attachments),
                            ),
                            ChatPlatformAdapter(
                                botUsername = ctx.botUserId,
                                historyLoader = { sessionId ->
                                    if (event.threadTs != null) {
                                        loadThreadHistory(ctx, event.threadTs, event.ts, sessionId, slackFileIngestor)
                                    } else {
                                        emptyList()
                                    }
                                },
                                reply = replyInSlack(ctx, event.threadTs),
                                activity = slackActivityIndicator(ctx, responseThreadTs),
                                fileIngestor = slackFileIngestor::ingest,
                            ),
                        )
                    }
                }
            }

            ctx.ack()
        }

        app.event(MessageFileShareEvent::class.java) { payload, ctx ->
            val event = payload.event
            log.debug("MessageFileShareEvent in ${event.channel}")
            val files = incomingChatFiles(event.files, event.attachments)
            if (eventDeduper.put(event.channel, event.ts)) {
                if (event.channelType != "im") {
                    async(app) {
                        val responseThreadTs = event.threadTs ?: event.ts
                        handleIncomingChatMessage.handle(
                            event.toConversationId(),
                            InboundMessage(
                                id = event.ts,
                                createdAtMs = slackTsToMillis(event.ts),
                                sender = toMessageAuthor(event.user, ctx),
                                text = event.text.orEmpty().trim(),
                                type =
                                    if (containsMention(
                                            event.text,
                                            ctx.botUserId,
                                        )
                                    ) {
                                        ChatMessageType.EXPLICIT_MENTION
                                    } else {
                                        ChatMessageType.PASSIVE_MESSAGE
                                    },
                                files = files,
                            ),
                            ChatPlatformAdapter(
                                botUsername = ctx.botUserId,
                                historyLoader = { sessionId ->
                                    if (event.threadTs != null) {
                                        loadThreadHistory(ctx, event.threadTs, event.ts, sessionId, slackFileIngestor)
                                    } else {
                                        emptyList()
                                    }
                                },
                                reply = replyInSlack(ctx, responseThreadTs),
                                activity = slackActivityIndicator(ctx, responseThreadTs),
                                fileIngestor = slackFileIngestor::ingest,
                            ),
                        )
                    }
                }
            }

            ctx.ack()
        }

        return app
    }
}

internal fun appHomeView(agentConfig: AgentConfig): View =
    View
        .builder()
        .type("home")
        .blocks(descriptionMarkdownBlocks(agentConfig))
        .build()

internal fun descriptionMarkdownBlocks(agentConfig: AgentConfig): List<LayoutBlock> {
    val path = agentConfig.workingDirectoryPath().resolve("DESCRIPTION.md")
    val markdown =
        when {
            Files.isRegularFile(path) -> Files.readString(path).trim()
            else -> "DESCRIPTION.md is not configured."
        }.ifBlank { "DESCRIPTION.md is empty." }

    val content = trimHomeMarkdown(markdown)
    return SlackHomeDescriptionBlocks.fromMarkdown(content).take(SLACK_HOME_MAX_BLOCKS)
}

private fun trimHomeMarkdown(markdown: String): String =
    if (markdown.length > SLACK_HOME_MAX_TOTAL_CHARS) {
        markdown.take(SLACK_HOME_MAX_TOTAL_CHARS - TRUNCATION_NOTICE.length) + TRUNCATION_NOTICE
    } else {
        markdown
    }

private fun logAppHomePublishFailure(
    response: ViewsPublishResponse,
    userId: String,
    view: View,
) {
    val blocks = view.blocks.orEmpty()
    log.warn(
        "Slack App Home publish failed: error={} needed={} provided={} response_messages={} response_warnings={} request_id={} user={} block_count={} max_block_text_length={}",
        response.error,
        response.needed,
        response.provided,
        response.responseMetadata?.messages,
        response.responseMetadata?.warnings,
        response.httpResponseHeaders?.get("x-slack-req-id")?.firstOrNull(),
        userId,
        blocks.size,
        blocks.maxOfOrNull { block ->
            when (block) {
                is HeaderBlock -> block.text?.text?.length ?: 0
                is SectionBlock -> block.text?.text?.length ?: 0
                else -> 0
            }
        } ?: 0,
    )
}

private const val SLACK_HOME_MAX_TOTAL_CHARS = 30_000
private const val SLACK_HOME_MAX_BLOCKS = 100
private const val TRUNCATION_NOTICE = "\n\n_DESCRIPTION.md truncated for Slack Home._"

internal fun buildSlackAssistant(
    app: App,
    deduper: HandledEventsDeduper,
    handleIncomingChatMessage: HandleIncomingChatMessageUsecase,
    slackFileIngestor: SlackFileIngestor,
): Assistant {
    val assistant = Assistant(app.executorService())

    assistant.userMessage { req, ctx ->
        val text = req.event.text
        if (text.isNullOrBlank() || !deduper.put(ctx.channelId, req.event.ts)) return@userMessage

        val conversationId = ChatConversationId(channelId = ctx.channelId, threadId = req.event.threadTs)
        val responseThreadTs = req.event.threadTs ?: req.event.ts
        runBlocking {
            handleIncomingChatMessage.handle(
                conversationId,
                InboundMessage(
                    id = req.event.ts,
                    createdAtMs = slackTsToMillis(req.event.ts),
                    sender = toMessageAuthor(req.event.user, ctx),
                    text = text.trim(),
                    type = ChatMessageType.ASSISTANT_MESSAGE,
                    files = emptyList(),
                ),
                ChatPlatformAdapter(
                    botUsername = ctx.botUserId,
                    historyLoader = { sessionId ->
                        if (req.event.threadTs != null) {
                            loadThreadHistory(ctx, req.event.threadTs, req.event.ts, sessionId, slackFileIngestor)
                        } else {
                            emptyList()
                        }
                    },
                    reply = replyInSlack(ctx, req.event.threadTs),
                    activity = slackActivityIndicator(ctx, responseThreadTs),
                    fileIngestor = slackFileIngestor::ingest,
                ),
            )
        }
    }

    assistant.userMessageWithFiles { req, ctx ->
        val text = req.event.text
        if (!deduper.put(ctx.channelId, req.event.ts)) return@userMessageWithFiles

        val conversationId = ChatConversationId(channelId = ctx.channelId, threadId = req.event.threadTs)
        val responseThreadTs = req.event.threadTs ?: req.event.ts
        runBlocking {
            handleIncomingChatMessage.handle(
                conversationId,
                InboundMessage(
                    id = req.event.ts,
                    createdAtMs = slackTsToMillis(req.event.ts),
                    sender = toMessageAuthor(req.event.user, ctx),
                    text = text.orEmpty().trim(),
                    type = ChatMessageType.ASSISTANT_MESSAGE,
                    files = incomingChatFiles(req.event.files, req.event.attachments),
                ),
                ChatPlatformAdapter(
                    botUsername = ctx.botUserId,
                    historyLoader = { sessionId ->
                        if (req.event.threadTs != null) {
                            loadThreadHistory(ctx, req.event.threadTs, req.event.ts, sessionId, slackFileIngestor)
                        } else {
                            emptyList()
                        }
                    },
                    reply = replyInSlack(ctx, req.event.threadTs),
                    activity = slackActivityIndicator(ctx, responseThreadTs),
                    fileIngestor = slackFileIngestor::ingest,
                ),
            )
        }
    }

    return assistant
}

internal fun async(
    app: App,
    block: suspend () -> Unit,
) {
    app.executorService().submit { runBlocking { block() } }
}

internal fun AppMentionEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)

internal fun MessageEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)

internal fun MessageFileShareEvent.toConversationId(): ChatConversationId = ChatConversationId(channel, threadTs)
