package com.github.uncomplexco.sidekick.application.utils

import org.slf4j.LoggerFactory

object Loggers {
    val TURN_EXECUTOR = LoggerFactory.getLogger("sidekick.turn-executor")
    val CONTEXT = LoggerFactory.getLogger("sidekick.context")
    val TOOLS = LoggerFactory.getLogger("sidekick.tools")
    val MCP = LoggerFactory.getLogger("sidekick.mcp")
    val SLACK = LoggerFactory.getLogger("sidekick.slack")
    val EXTENSIONS = LoggerFactory.getLogger("sidekick.extensions")
}
