package com.github.uncomplexco.sidekick.application.conversation.triggers

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.KoogConfig
import com.github.uncomplexco.sidekick.application.agent.openRouterExecutor
import com.github.uncomplexco.sidekick.application.turn.ReplyDecision
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionInput
import com.github.uncomplexco.sidekick.application.turn.ReplyDecisionReason
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
