package com.github.uncomplexco.sidekick.application.tools.system

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
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
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.context.SystemPromptBuilder
import com.github.uncomplexco.sidekick.application.context.TurnPromptBuilder
import com.github.uncomplexco.sidekick.application.conversation.AiModelProfile
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.application.conversation.SessionMessage
import com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole
import com.github.uncomplexco.sidekick.application.turn.ConversationHistory
import com.github.uncomplexco.sidekick.application.turn.TurnContext
import com.github.uncomplexco.sidekick.application.utils.Loggers
import kotlin.reflect.KClass
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component

class LoopTools(
    private val factory: LoopFactory,
    private val chat: ChatPlatformAdapter,
    private val ctx: TurnContext,
    private val bindTools: suspend (TurnContext) -> ToolRegistry,
) : ToolSet {
    @Tool("Loop")
    @LLMDescription("Spawns a subagent to execute a loop in fresh context.")
    fun loop(
        @LLMDescription("Instructions how to validate the loop has completed.") validation: String,
        @LLMDescription("Instructions what the agent should do each iteration.") iteration: String,
    ): Loop.ValidationResult {
        val loop = factory.create(chat = chat)

        return loop.run(
            parent = ctx,
            userId = "anonymous",
            validationPrompt = validation,
            iterationPrompt = iteration,
            bindTools = bindTools,
        )
    }
}

@Component
class LoopFactory(
    private val koogConfig: KoogConfig,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val turnPromptBuilder: TurnPromptBuilder,
) {
    fun create(chat: ChatPlatformAdapter): Loop =
        Loop(koogConfig, systemPromptBuilder, turnPromptBuilder, chat = chat)
}

class Loop(
    private val koogConfig: KoogConfig,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val turnPromptBuilder: TurnPromptBuilder,
    private val chat: ChatPlatformAdapter,
) {
    fun run(
        parent: TurnContext,
        userId: String,
        validationPrompt: String,
        iterationPrompt: String,
        bindTools: suspend (TurnContext) -> ToolRegistry,
    ): ValidationResult = runBlocking {
        Loggers.TOOLS_LOOP.info(
            """
                Loop:
                - Validation prompt: $validationPrompt
                - Iteration prompt: $iterationPrompt
                """
                .trimIndent()
        )

        val maxIterations = 15
        var iterationsLeft = maxIterations
        while (iterationsLeft-- > 0) {
            val validation =
                runAgent(
                    message = wrapPrompt(validationPrompt, userId),
                    parent = parent,
                    chat = chat,
                    bindTools = bindTools,
                    ValidationResult::class,
                )

            if (validation.isCompleted) {
                Loggers.TOOLS_LOOP.info("Loop finished. Remaining iterations: $iterationsLeft")
                return@runBlocking validation
            }

            Loggers.TOOLS_LOOP.info(
                """
                    Iteration ${maxIterations - iterationsLeft} start.
                    Validation response: ${validation.reason}
                    """
                    .trimIndent()
            )

            val result =
                runAgent(
                    message = wrapPrompt(iterationPrompt, "anonymous"),
                    parent = parent,
                    chat = chat,
                    bindTools = bindTools,
                )

            Loggers.TOOLS_LOOP.info(
                """
                    Iteration ${maxIterations - iterationsLeft} finished.
                    Agent response: $result
                    """
                    .trimIndent()
            )
        }

        Loggers.TOOLS_LOOP.error("Loop ran out of iterations.")
        return@runBlocking ValidationResult(isCompleted = false, reason = "Ran out of iterations.")
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
        parent: TurnContext,
        chat: ChatPlatformAdapter,
        bindTools: suspend (TurnContext) -> ToolRegistry,
    ): String {
        val ctx = turnContext(parent, message.id)

        val aiModelProfile = koogConfig.profile(AiModelProfile.NORMAL)
        val model = llModel(aiModelProfile)
        val agent =
            AIAgent(
                strategy = singleRunStrategy(),
                promptExecutor = koogConfig.openRouterExecutor(),
                agentConfig = agentConfig(ctx, chat, aiModelProfile, model),
                toolRegistry = bindTools(ctx),
            )

        return agent.run(
            turnPromptBuilder.buildSessionTurnPrompt(message, ctx),
            ctx.conversation.conversationId.lockKey(),
        )
    }

    private suspend inline fun <reified T : Any> runAgent(
        message: SessionMessage,
        parent: TurnContext,
        chat: ChatPlatformAdapter,
        bindTools: suspend (TurnContext) -> ToolRegistry,
        outputClass: KClass<T>,
    ): T {
        val ctx = turnContext(parent, message.id)

        require(outputClass == T::class) { "Output class must match reified output type." }
        val structure = JsonStructure.create<T>()
        val structuredOutputConfig =
            StructuredRequestConfig(default = StructuredRequest.Manual(structure))
        val aiModelProfile = koogConfig.profile(AiModelProfile.NORMAL)
        val model = llModel(aiModelProfile)
        val agent =
            AIAgent(
                strategy =
                    structuredOutputWithToolsStrategy<String, T>(structuredOutputConfig) { input ->
                        input
                    },
                promptExecutor = koogConfig.openRouterExecutor(),
                agentConfig = agentConfig(ctx, chat, aiModelProfile, model),
                toolRegistry = bindTools(ctx),
            )

        return agent.run(
            turnPromptBuilder.buildSessionTurnPrompt(message, ctx),
            ctx.conversation.conversationId.lockKey(),
        )
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
                    params =
                        koogConfig.openRouterParams(
                            aiModelProfile,
                            ctx.conversation.conversationId,
                        ),
                ) {
                    system(
                        systemPromptBuilder.buildSystemPrompt(
                            chat.botUsername,
                            ctx.conversation.virtualPaths.projectRoot,
                        )
                    )
                },
            model = model,
            maxAgentIterations = koogConfig.maxAgentIterations,
        )

    @OptIn(ExperimentalUuidApi::class)
    private fun turnContext(
        parent: TurnContext,
        messageId: String,
    ): TurnContext =
        parent.copy(
            conversation =
                parent.conversation.copy(
                    history =
                        ConversationHistory(
                            compactions = emptyList(),
                            messages = emptyList(),
                            hasKoogMessages = false,
                        )
                ),
            turnId = "loop_${Uuid.generateV7()}",
            lastMessageId = messageId,
            aiModelProfile = AiModelProfile.NORMAL,
        )

    @Serializable
    data class ValidationResult(
        val isCompleted: Boolean,
        val reason: String?,
    )
}
