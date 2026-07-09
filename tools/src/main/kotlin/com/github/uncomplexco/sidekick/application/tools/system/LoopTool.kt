package com.github.uncomplexco.sidekick.application.tools.system

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.structuredOutputWithToolsStrategy
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.structure.StructuredRequest
import ai.koog.prompt.structure.StructuredRequestConfig
import ai.koog.prompt.structure.json.JsonStructure
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.LlmProfile
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.ConversationContext
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.turn.koog.McpServersRegistry
import com.github.uncomplexco.sidekick.application.turn.koog.ToolRegistryFactory
import com.github.uncomplexco.sidekick.application.turn.koog.sidekickStrategy
import com.github.uncomplexco.sidekick.application.utils.Loggers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Component
class LoopToolFactory {
    fun create(
        koogConfig: KoogConfig,
        systemPromptBuilder: SystemPromptBuilder,
        turnPromptBuilder: TurnPromptBuilder,
        virtualPathsFactory: VirtualPathsFactory,
        toolRegistryFactory: ToolRegistryFactory,
        mcpServersRegistry: McpServersRegistry,
        chat: ChatPlatformAdapter,
    ): Loop = Loop(koogConfig, systemPromptBuilder, turnPromptBuilder, virtualPathsFactory, toolRegistryFactory, mcpServersRegistry, chat)
}

class Loop(
    private val koogConfig: KoogConfig,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val turnPromptBuilder: TurnPromptBuilder,
    private val virtualPathsFactory: VirtualPathsFactory,
    private val mcpServersRegistry: McpServersRegistry,
    private val chat: ChatPlatformAdapter,
) {
    fun run(
        validationPrompt: String,
        iterationPrompt: String,
        toolRegistry: ToolRegistry,
    ) {
        Loggers.TOOLS_LOOP.info(
            """
            Loop:
            - Validation prompt: $validationPrompt
            - Iteration prompt: $iterationPrompt
            """.trimIndent(),
        )

        runBlocking {
            val maxIterations = 15
            var iterationsLeft = maxIterations
            while (iterationsLeft-- > 0) {
                val validation = runAgent(wrapPrompt(validationPrompt, "anonymous"), chat, toolRegistry, ValidationResult::class)
                if (validation.isCompleted) break

                Loggers.TOOLS_LOOP.info(
                    """
                    Iteration ${maxIterations - iterationsLeft} start.
                    Validation response: ${validation.reason}
                    """.trimIndent(),
                )

                val result = runAgent(wrapPrompt(iterationPrompt, "anonymous"), chat, toolRegistry)

                Loggers.TOOLS_LOOP.info(
                    """
                    Iteration ${maxIterations - iterationsLeft} finished.
                    Agent response: $result
                    """.trimIndent(),
                )
            }

            Loggers.TOOLS_LOOP.info("Loop finished. Remaining iterations: $iterationsLeft")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun wrapPrompt(
        prompt: String,
        requester: String,
    ): SessionMessage =
        SessionMessage(
            id = "loop_${Uuid.generateV7()}",
            role = SessionMessageRole.USER,
            author = MessageAuthor(username = requester, fullName = null),
            text = prompt,
            createdAtMs = System.currentTimeMillis(),
            explicitMention = true,
        )

    private suspend fun runAgent(
        message: SessionMessage,
        chat: ChatPlatformAdapter,
        toolRegistry: ToolRegistry,
        virtualPaths: VirtualPaths,
    ): String {
        val ctx = turnContext(CLI_CONVERSATION, virtualPaths, message.id)
        val ctxWithMcp = ctx.withConnectedMcp(message.author?.username)

        try {
            val aiModelProfile = koogConfig.profile(AiModelProfile.NORMAL)
            val model = llModel(aiModelProfile)
            val agent =
                AIAgent(
                    strategy = sidekickStrategy(),
                    promptExecutor = openRouterExecutor(koogConfig.openRouterApiKey),
                    agentConfig = agentConfig(ctx, chat, aiModelProfile, model),
                    toolRegistry = toolRegistry,
                )

            return agent.run(turnPromptBuilder.buildSessionTurnPrompt(message, ctxWithMcp), ctx.conversation.conversationId.lockKey())
        } finally {
            ctxWithMcp.conversation.mcpServers.forEach { it.close() }
        }
    }

    private suspend inline fun <reified T : Any> runAgent(
        message: SessionMessage,
        chat: ChatPlatformAdapter,
        toolRegistry: ToolRegistry,
        virtualPaths: VirtualPaths,
        outputClass: KClass<T>,
    ): T {
        val ctx = turnContext(CLI_CONVERSATION, virtualPaths, message.id)
        val ctxWithMcp = ctx.withConnectedMcp(message.author?.username)

        try {
            require(outputClass == T::class) { "Output class must match reified output type." }
            val structure = JsonStructure.create<T>()
            val structuredOutputConfig = StructuredRequestConfig(default = StructuredRequest.Manual(structure))
            val aiModelProfile = koogConfig.profile(AiModelProfile.NORMAL)
            val model = llModel(aiModelProfile)
            val agent =
                AIAgent(
                    strategy = structuredOutputWithToolsStrategy<String, T>(structuredOutputConfig) { input -> input },
                    promptExecutor = openRouterExecutor(koogConfig.openRouterApiKey),
                    agentConfig = agentConfig(ctx, chat, aiModelProfile, model),
                    toolRegistry = toolRegistry,
                )

            return agent.run(turnPromptBuilder.buildSessionTurnPrompt(message, ctxWithMcp), ctx.conversation.conversationId.lockKey())
        } finally {
            ctxWithMcp.conversation.mcpServers.forEach { it.close() }
        }
    }

    private suspend fun TurnContext.withConnectedMcp(userId: String?): TurnContext {
        if (userId.isNullOrBlank()) {
            return this
        }

        val mcpServers = mcpServersRegistry.connect(conversation.conversationId, userId)
        return copy(conversation = conversation.copy(mcpServers = mcpServers))
    }

    private fun llModel(aiModelProfile: LlmProfile): LLModel =
        LLModel(
            provider = LLMProvider.OpenRouter,
            id = aiModelProfile.model,
            capabilities = koogConfig.modelCapabilities(),
        )

    private fun agentConfig(
        ctx: TurnContext,
        chat: ChatPlatformAdapter,
        aiModelProfile: LlmProfile,
        model: LLModel,
    ): AIAgentConfig =
        AIAgentConfig(
            prompt =
                prompt(
                    id = "sidekick-test-prompt",
                    params = koogConfig.openRouterParams(aiModelProfile),
                ) {
                    system(systemPromptBuilder.buildSystemPrompt(chat.botUsername, ctx.conversation.conversationId))
                },
            model = model,
            maxAgentIterations = koogConfig.maxAgentIterations,
        )

    @OptIn(ExperimentalUuidApi::class)
    private fun turnContext(
        conversationId: ConversationId,
        virtualPaths: VirtualPaths,
        messageId: String,
    ): TurnContext =
        TurnContext(
            conversation =
                ConversationContext(
                    conversationId = conversationId,
                    virtualPaths = virtualPaths,
                    history = ConversationHistory(compactions = emptyList(), messages = emptyList(), hasKoogMessages = false),
                    mcpServers = emptyList(),
                ),
            turnId = "loop_${Uuid.generateV7()}",
            currentMessageIds = listOf(messageId),
            currentFiles = emptyList(),
            sessionFiles = emptyList(),
            aiModelProfile = AiModelProfile.NORMAL,
        )

    @Serializable
    private data class ValidationResult(
        val isCompleted: Boolean,
        val reason: String?,
    )

    companion object {
        private val CLI_CONVERSATION = ConversationId(channelId = "cli", threadId = "default")
    }
}
