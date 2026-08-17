package com.github.uncomplexco.sidekick.application.utils

import org.slf4j.LoggerFactory

object Loggers {
    val TURN_EXECUTOR = LoggerFactory.getLogger("sidekick.turn-executor")
    val CONTEXT = LoggerFactory.getLogger("sidekick.context")
    val TOOLS = LoggerFactory.getLogger("sidekick.tools")
    val TOOLS_LOOP = LoggerFactory.getLogger("sidekick.tools.loop")
    val MCP = LoggerFactory.getLogger("sidekick.mcp")
    val SLACK = LoggerFactory.getLogger("sidekick.slack")
    val WEEKLY_STATS = LoggerFactory.getLogger("sidekick.weekly-stats")
    val SCHEDULED_JOBS = LoggerFactory.getLogger("sidekick.scheduled-jobs")
    val EXTENSIONS = LoggerFactory.getLogger("sidekick.extensions")
}
