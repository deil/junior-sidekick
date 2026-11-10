package com.github.uncomplexco.sidekick.application.core

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.chat.ChatPlatform
import com.github.uncomplexco.sidekick.application.utils.sanitizePathSegment
import com.github.uncomplexco.sidekick.ports.ProjectsRepository
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Projects::class.java)

@Component
class Projects(
    private val projects: ProjectsRepository,
    private val config: AgentConfig,
    private val platform: ChatPlatform,
) : InitializingBean {
    override fun afterPropertiesSet() {
        seedExistingProjects()
    }

    fun seedExistingProjects() {
        if (!projects.isEmpty()) return

        val root =
            config.stateDirectoryPath().resolve(platform.name.lowercase()).resolve("channels")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return

        val now = Instant.now()
        val discovered =
            Files.newDirectoryStream(root).use { folders ->
                folders
                    .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
                    .sortedBy { it.fileName.toString() }
                    .map { folder ->
                        val uid = folder.fileName.toString()
                        project(uid, uid, now)
                    }
            }

        if (discovered.isNotEmpty() && projects.seedIfEmpty(discovered)) {
            log.info("Seeded {} projects from {}", discovered.size, root)
        }
    }

    fun register(uid: String, channelId: String) {
        if (projects.addIfAbsent(project(uid, channelId, Instant.now()))) {
            log.info("Registered project {} for {} channel {}", uid, platform, channelId)
        }
    }

    fun exists(channelId: String): Boolean =
        projects.findByUid(sanitizePathSegment(channelId)) != null

    fun register(channelId: String, type: ProjectType?) {
        val uid = sanitizePathSegment(channelId)
        if (projects.findByUid(uid) != null) return

        val defaults = project(uid, channelId, Instant.now())
        val project = defaults.copy(type = type ?: defaults.type)

        if (projects.addIfAbsent(project)) {
            log.info(
                "Registered project {} for {} channel {} type={}",
                uid,
                platform,
                channelId,
                project.type,
            )
        }
    }

    private fun project(uid: String, channelId: String, createdAt: Instant): Project =
        Project(
            uid = uid,
            adapter = platform,
            name = uid,
            externalRefId = channelId,
            createdAt = createdAt,
            type =
                if (platform == ChatPlatform.SLACK && uid.startsWith("D")) ProjectType.DM
                else ProjectType.PRIVATE,
        )
}
