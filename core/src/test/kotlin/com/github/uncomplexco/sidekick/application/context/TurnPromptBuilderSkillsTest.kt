package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.agent.skills.Skill
import com.github.uncomplexco.sidekick.application.agent.skills.SkillCatalog
import com.github.uncomplexco.sidekick.application.conversation.ExplicitSkillInvocation
import com.github.uncomplexco.sidekick.application.context.prompts.CURRENT_INSTRUCTION_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.EXPLICIT_SKILL_INVOCATION_TAG
import com.github.uncomplexco.sidekick.application.context.prompts.RUNTIME_CONTEXT_TAG
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnPromptBuilderSkillsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `renders model invocable skills catalog`() {
        // Arrange
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill(
                            "model-skill",
                            "Use when model should load this skill.",
                            disableModelInvocation = false,
                            userInvocable = true,
                        ),
                    ),
                ),
            ).buildSessionTurnPrompt(message(), context())

        // Assert
        assertTrue(prompt.contains("<skills>"), prompt)
        assertTrue(prompt.contains("<available_skills>"), prompt)
        assertTrue(prompt.contains("<user_invocable_skills>"), prompt)
        assertTrue(prompt.contains("<name>model-skill</name>"), prompt)
        assertTrue(prompt.contains("<description>Use when model should load this skill.</description>"), prompt)
        assertTrue(prompt.contains("<location>skills:/repo/model-skill/SKILL.md</location>"), prompt)
        assertTrue(prompt.indexOf("<skills>") > prompt.indexOf("<$RUNTIME_CONTEXT_TAG>"), prompt)
        assertTrue(prompt.indexOf("<skills>") < prompt.lastIndexOf("<$CURRENT_INSTRUCTION_TAG>"), prompt)
        assertTrue(prompt.contains("<$CURRENT_INSTRUCTION_TAG>"), prompt)
    }

    @Test
    fun `does not render skills when koog history exists`() {
        // Arrange
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill("model-skill", "Use when model should load this skill.", disableModelInvocation = false),
                    ),
                ),
            ).buildSessionTurnPrompt(message(), context(hasKoogMessages = true))

        // Assert
        assertFalse(prompt.contains("<skills>"), prompt)
        assertFalse(prompt.contains("model-skill"), prompt)
    }

    @Test
    fun `renders user invocable skills disabled for model invocation`() {
        // Arrange
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill(
                            "disabled-skill",
                            "User-only skill.",
                            disableModelInvocation = true,
                            userInvocable = true,
                        ),
                    ),
                ),
            ).buildSessionTurnPrompt(message(), context())

        // Assert
        assertFalse(prompt.contains("<available_skills>"), prompt)
        assertTrue(prompt.contains("<user_invocable_skills>"), prompt)
        assertTrue(prompt.contains("<name>disabled-skill</name>"), prompt)
    }

    @Test
    fun `does not render user invocable section for skills disabled for user invocation`() {
        // Arrange
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill(
                            "model-only-skill",
                            "Model-only skill.",
                            disableModelInvocation = false,
                            userInvocable = false,
                        ),
                    ),
                ),
            ).buildSessionTurnPrompt(message(), context())

        // Assert
        assertTrue(prompt.contains("<available_skills>"), prompt)
        assertFalse(prompt.contains("<user_invocable_skills>"), prompt)
        assertTrue(prompt.contains("<name>model-only-skill</name>"), prompt)
    }

    @Test
    fun `omits skills section when no skills are model or user invocable`() {
        // Arrange
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill(
                            "hidden-skill",
                            "Hidden skill.",
                            disableModelInvocation = true,
                            userInvocable = false,
                        ),
                    ),
                ),
            ).buildSessionTurnPrompt(message(), context())

        // Assert
        assertFalse(prompt.contains("<skills>"), prompt)
        assertFalse(prompt.contains("hidden-skill"), prompt)
    }

    @Test
    fun `omits skills section when catalog is empty`() {
        // Act
        val prompt = builder(SkillCatalog(emptyList())).buildSessionTurnPrompt(message(), context())

        // Assert
        assertFalse(prompt.contains("<skills>"), prompt)
        assertFalse(prompt.contains("<available_skills>"), prompt)
    }

    @Test
    fun `renders explicit skill invocation before current instruction`() {
        // Arrange
        val message = message(text = "please /code-review this", explicitSkillInvocation = ExplicitSkillInvocation("code-review"))
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill(
                            "code-review",
                            "Review code.",
                            disableModelInvocation = false,
                            userInvocable = true,
                        ),
                    ),
                ),
            ).buildSessionTurnPrompt(message, context())

        // Assert
        assertTrue(prompt.contains("<$EXPLICIT_SKILL_INVOCATION_TAG>"), prompt)
        assertTrue(prompt.contains("/code-review"), prompt)
        assertTrue(prompt.indexOf("<$EXPLICIT_SKILL_INVOCATION_TAG>") < prompt.lastIndexOf("<$CURRENT_INSTRUCTION_TAG>"), prompt)
        assertTrue(prompt.contains("<$CURRENT_INSTRUCTION_TAG>\n[alice] please /code-review this\n</$CURRENT_INSTRUCTION_TAG>"), prompt)
    }

    @Test
    fun `omits explicit skill invocation when message has no materialized invocation`() {
        // Arrange
        val message = message(text = "please review this")
        val prompt =
            builder(
                SkillCatalog(
                    listOf(
                        skill(
                            "code-review",
                            "Review code.",
                            disableModelInvocation = false,
                            userInvocable = true,
                        ),
                    ),
                ),
            ).buildSessionTurnPrompt(message, context())

        // Assert
        assertFalse(prompt.contains("<$EXPLICIT_SKILL_INVOCATION_TAG>"), prompt)
    }

    private fun builder(catalog: SkillCatalog): TurnPromptBuilder =
        TurnPromptBuilder(
            config =
                AgentConfig(
                    name = "Sidekick",
                    stateDir = dir.resolve("state").toString(),
                    workingDir = dir.resolve("workspace").toString(),
                ),
            skills = { catalog },
        )

    private fun context(hasKoogMessages: Boolean = false): com.github.uncomplexco.sidekick.application.turn.TurnContext =
        com.github.uncomplexco.sidekick.application.turn.TurnContext(
            conversationId = com.github.uncomplexco.sidekick.application.conversation.ConversationId("C123", "1700000000.000"),
            turnId = "turn",
            currentMessageIds = listOf("m1"),
            currentFiles = emptyList(),
            sessionFiles = emptyList(),
            history =
                com.github.uncomplexco.sidekick.application.turn.ConversationHistory(
                    compactions = emptyList(),
                    messages = emptyList(),
                    hasKoogMessages = hasKoogMessages,
                ),
            mcpServers = emptyList(),
        )

    private fun message(
        text: String = "use a skill",
        explicitSkillInvocation: ExplicitSkillInvocation? = null,
    ): com.github.uncomplexco.sidekick.application.conversation.SessionMessage =
        com.github.uncomplexco.sidekick.application.conversation.SessionMessage(
            id = "m1",
            role = com.github.uncomplexco.sidekick.application.conversation.SessionMessageRole.USER,
            author = com.github.uncomplexco.sidekick.application.conversation.MessageAuthor(username = "alice", fullName = "Alice"),
            text = text,
            createdAtMs = 1,
            explicitSkillInvocation = explicitSkillInvocation,
        )

    private fun skill(
        name: String,
        description: String,
        disableModelInvocation: Boolean,
        userInvocable: Boolean = false,
    ): Skill =
        Skill(
            name = name,
            description = description,
            folder = dir.resolve("workspace/skills/repo/$name"),
            disableModelInvocation = disableModelInvocation,
            userInvocable = userInvocable,
        )
}
