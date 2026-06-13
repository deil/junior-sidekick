package com.github.uncomplexco.sidekick.application.agent.skills

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSkillInvocationTest {
    @TempDir
    lateinit var dir: Path

    @ParameterizedTest(name = "detects positive phrase: {0}")
    @ValueSource(
        strings = [
            "please run /code-review on this",
            "/code-review please review this",
            "can you check this with /code-review?",
            "<@U123> /code-review review this PR",
            "review this PR /code-review",
            "please run /unknown and then /code-review",
            "please no use code-review for this PR",
            "please without use code-review for this PR",
            "use code-review for this PR",
            "activate code-review for this PR",
            "invoke code-review for this PR",
            "run code-review for this PR",
            "call code-review for this PR",
            "use skill code-review for this PR",
            "activate skill code-review for this PR",
            "invoke skill code-review for this PR",
            "run skill code-review for this PR",
            "call skill code-review for this PR",
            "use the code-review for this PR",
            "activate the code-review for this PR",
            "invoke the code-review for this PR",
            "run the code-review for this PR",
            "call the code-review for this PR",
            "use code-review skill for this PR",
            "activate code-review skill for this PR",
            "invoke code-review skill for this PR",
            "run code-review skill for this PR",
            "call code-review skill for this PR",
            "use the code-review skill for this PR",
            "activate the code-review skill for this PR",
            "invoke the code-review skill for this PR",
            "run the code-review skill for this PR",
            "call the code-review skill for this PR",
            "Use Code-Review for this PR",
            "/Code-Review review this",
            "without /code-review please",
            "no /code-review on this",
            "please use code-review. then summarize",
            "please use code-review, then summarize",
            "please use code-review: then summarize",
            "please use code-review; then summarize",
        ],
    )
    fun `detects positive phrases`(text: String) {
        // Arrange
        val catalog = catalog(skill("code-review", userInvocable = true))

        // Act
        val invocation = detectUserSkillInvocation(text, catalog)

        // Assert
        assertEquals("code-review", invocation?.skill?.name)
    }

    @ParameterizedTest(name = "ignores negative phrase: {0}")
    @ValueSource(
        strings = [
            "do not use code-review for this PR",
            "please do not use code-review for this PR",
            "do not activate code-review",
            "do not invoke skill code-review",
            "do not run code-review",
            "do not call skill code-review",
            "do not use the code-review skill",
            "don't use code-review for this PR",
            "please don't activate code-review",
            "don't invoke skill code-review",
            "don't run code-review",
            "don't call skill code-review",
            "don't use the code-review skill",
            "dont use code-review",
            "never use code-review",
            "never activate skill code-review",
            "never invoke the code-review skill",
            "never run code-review",
            "never call skill code-review",
        ],
    )
    fun `ignores negative phrases`(text: String) {
        // Arrange
        val catalog = catalog(skill("code-review", userInvocable = true))

        // Act
        val invocation = detectUserSkillInvocation(text, catalog)

        // Assert
        assertNull(invocation)
    }

    @ParameterizedTest(name = "ignores boundary phrase: {0}")
    @ValueSource(
        strings = [
            "use code-reviewer for this",
            "use my-code-review for this",
            "/code-reviewer review this",
            "/my-code-review review this",
            "reuse code-review for this",
            "misuse code-review for this",
            "abuse code-review for this",
            "overrun code-review for this",
            "recall code-review for this",
            "reactivate code-review for this",
            "reinvoke code-review for this",
        ],
    )
    fun `ignores boundary mismatches`(text: String) {
        // Arrange
        val catalog = catalog(skill("code-review", userInvocable = true))

        // Act
        val invocation = detectUserSkillInvocation(text, catalog)

        // Assert
        assertNull(invocation)
    }

    @Test
    fun `ignores unknown skill names`() {
        // Arrange
        val catalog = catalog(skill("known-skill", userInvocable = true))

        // Act
        val invocation = detectUserSkillInvocation("use missing-skill for this", catalog)

        // Assert
        assertNull(invocation)
    }

    @Test
    fun `ignores unknown slash skill names`() {
        // Arrange
        val catalog = catalog(skill("known-skill", userInvocable = true))

        // Act
        val invocation = detectUserSkillInvocation("/missing-skill review this", catalog)

        // Assert
        assertNull(invocation)
    }

    @Test
    fun `ignores skills that are not user invocable`() {
        // Arrange
        val catalog = catalog(skill("code-review", userInvocable = false))

        // Act
        val invocation = detectUserSkillInvocation("use code-review for this", catalog)

        // Assert
        assertNull(invocation)
    }

    @Test
    fun `slash invocation wins over earlier natural language invocation`() {
        // Arrange
        val catalog =
            catalog(
                skill("second-skill", userInvocable = true),
                skill("first-skill", userInvocable = true),
            )

        // Act
        val invocation = detectUserSkillInvocation("use first-skill then /second-skill", catalog)

        // Assert
        assertEquals("second-skill", invocation?.skill?.name)
    }

    @Test
    fun `chooses earliest slash invocation regardless catalog order`() {
        // Arrange
        val catalog =
            catalog(
                skill("beta-skill", userInvocable = true),
                skill("alpha-skill", userInvocable = true),
            )

        // Act
        val invocation = detectUserSkillInvocation("/beta-skill and /alpha-skill", catalog)

        // Assert
        assertEquals("beta-skill", invocation?.skill?.name)
    }

    private fun catalog(vararg skills: Skill): SkillCatalog = SkillCatalog(skills.toList())

    private fun skill(
        name: String,
        userInvocable: Boolean,
    ): Skill =
        Skill(
            name = name,
            description = "$name description",
            folder = dir.resolve(name),
            disableModelInvocation = false,
            userInvocable = userInvocable,
        )
}
