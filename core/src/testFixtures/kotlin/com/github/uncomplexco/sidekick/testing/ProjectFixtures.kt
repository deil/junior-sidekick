package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.adapters.files.LocalWorkspaceFilesystem
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPathsFactory
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.core.Project
import com.github.uncomplexco.sidekick.application.core.Projects
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import com.github.uncomplexco.sidekick.ports.filesystem.WorkspaceFilesystemFactory

class NoOpProjectsRepository : ProjectsRepository {
    override fun isEmpty(): Boolean = true

    override fun findByUid(uid: String): Project? = null

    override fun findById(id: Long): Project? = null

    override fun addIfAbsent(project: Project): Boolean = false

    override fun updateChannelDetails(project: Project) = Unit

    override fun seedIfEmpty(projects: List<Project>): Boolean = false
}

fun virtualPathsFactory(config: AgentConfig, platform: ChatPlatform): VirtualPathsFactory =
    VirtualPathsFactory(
        config,
        platform,
        Projects(NoOpProjectsRepository(), config, platform),
        WorkspaceFilesystemFactory(::LocalWorkspaceFilesystem),
    )

fun projectsService(config: AgentConfig, platform: ChatPlatform = ChatPlatform.SLACK): Projects =
    Projects(NoOpProjectsRepository(), config, platform)
