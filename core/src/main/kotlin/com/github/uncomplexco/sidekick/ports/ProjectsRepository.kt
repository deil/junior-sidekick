package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.core.Project

interface ProjectsRepository {
    fun isEmpty(): Boolean

    fun findByUid(uid: String): Project?

    fun addIfAbsent(project: Project): Boolean

    fun updateChannelDetails(project: Project)

    fun seedIfEmpty(projects: List<Project>): Boolean
}
