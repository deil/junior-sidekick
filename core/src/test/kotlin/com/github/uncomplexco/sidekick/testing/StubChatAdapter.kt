package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.adapters.files.FilesystemConversationStateStore
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.AgentResponse
import com.github.uncomplexco.sidekick.application.agent.AgentSteering
import com.github.uncomplexco.sidekick.application.agent.AgentTurnExecutor
import com.github.uncomplexco.sidekick.application.agent.TurnExecutionResult
import com.github.uncomplexco.sidekick.application.chat.ChannelVisibility
import com.github.uncomplexco.sidekick.application.chat.ChatMessage
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.chat.ChatPlatformAdapter
import com.github.uncomplexco.sidekick.application.chat.ChatUserDetails
import com.github.uncomplexco.sidekick.application.chat.IncomingChatFile
import com.github.uncomplexco.sidekick.application.chat.IncomingMessage
import com.github.uncomplexco.sidekick.application.chat.PublishedMessage
import com.github.uncomplexco.sidekick.application.chat.TurnLifecycle
import com.github.uncomplexco.sidekick.application.context.ProjectContexts
import com.github.uncomplexco.sidekick.application.context.SessionContextCompactor
import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.SessionManager
import com.github.uncomplexco.sidekick.application.core.Projects
import com.github.uncomplexco.sidekick.application.core.UsersService
import com.github.uncomplexco.sidekick.application.stats.AgentStats
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.turn.DeterministicParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.LlmParticipationClassifier
import com.github.uncomplexco.sidekick.application.turn.ResponseDelivery
import com.github.uncomplexco.sidekick.application.turn.ResponseMetadata
import com.github.uncomplexco.sidekick.application.turn.TurnAdmission
import com.github.uncomplexco.sidekick.application.turn.TurnCoordinator
import com.github.uncomplexco.sidekick.application.turn.TurnParticipation
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.github.uncomplexco.sidekick.ports.UsersRepository
import java.time.Clock
import org.mockito.Mockito.mock

class StubChatAdapter(
    private val profiles: Map<String, ChatUserDetails> = emptyMap(),
    private val channelVisibilities: Map<String, ChannelVisibility> = emptyMap(),
) : ChatPlatformAdapter {
    val requestedUsers = mutableListOf<String>()
    val replies = mutableListOf<String>()
    val requestedProjects = mutableListOf<String>()
    override val botUsername = "UBOT"
    private val output =
        object : TurnLifecycle, ResponseDelivery {
            override fun begin() = Unit

            override fun progress(text: String?) = Unit

            override fun end() = Unit

            override suspend fun markProcessing(message: IncomingMessage) = Unit

            override suspend fun markCompleted(message: IncomingMessage) = Unit

            override suspend fun markFailed(message: IncomingMessage) = Unit

            override suspend fun deliver(
                response: AgentResponse,
                metadata: ResponseMetadata,
            ): PublishedMessage {
                replies += response.text
                return PublishedMessage("reply", 1000)
            }
        }
    override val lifecycle: TurnLifecycle = output
    override val delivery: ResponseDelivery = output

    override suspend fun loadHistory(conversationId: ConversationId): List<ChatMessage> =
        emptyList()

    override suspend fun ingestFiles(
        conversationId: ConversationId,
        files: List<IncomingChatFile>,
    ): List<IncomingChatFile> = files

    override suspend fun loadUserDetails(chatId: String): ChatUserDetails? {
        requestedUsers += chatId
        return profiles[chatId]
    }

    override suspend fun loadChannelVisibility(channelId: String): ChannelVisibility? {
        requestedProjects += channelId
        return channelVisibilities[channelId]
    }
}

fun userInteractionCoordinator(
    config: AgentConfig,
    users: UsersRepository,
    clock: Clock,
    projects: ProjectsRepository = NoOpProjectsRepository(),
    duringExecution: suspend (AgentSteering) -> Unit = {},
): TurnCoordinator {
    config.botUsername = "UBOT"
    val sessions =
        SessionManager(
            FilesystemConversationStateStore(config, ChatPlatform.SLACK),
            virtualPathsFactory(config, ChatPlatform.SLACK),
            SessionContextCompactor { _, _, _ -> error("Unexpected compaction") },
            ProjectContexts(),
        )
    return TurnCoordinator(
        TurnAdmission(sessions),
        sessions,
        TurnParticipation(
            config,
            DeterministicParticipationClassifier(),
            mock(LlmParticipationClassifier::class.java),
        ),
        AgentTurnExecutor { _, _, steering ->
            duringExecution(steering)
            TurnExecutionResult.Completed(
                AgentResponse("Done"),
                AgentStats("normal", AgentUsageStats()),
            )
        },
        StubSkillsCatalog(),
        UsersService(users, clock),
        Projects(projects, config, ChatPlatform.SLACK),
    )
}
