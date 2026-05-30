package com.github.uncomplexco.sidekick.application.agent

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfig(
    @Value($$"${agent.name}") val name: String,
)
