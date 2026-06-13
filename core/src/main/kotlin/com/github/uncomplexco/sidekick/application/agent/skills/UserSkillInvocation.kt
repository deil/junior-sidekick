package com.github.uncomplexco.sidekick.application.agent.skills

data class UserSkillInvocation(
    val skill: Skill,
)

fun detectUserSkillInvocation(
    text: String,
    catalog: SkillCatalog,
): UserSkillInvocation? {
    val userInvocableSkills = catalog.skills.filter { it.userInvocable }

    slashMatches(text, userInvocableSkills)
        .minWithOrNull(compareBy<UserSkillInvocationMatch> { it.range.first }.thenBy { it.skill.name })
        ?.let { return UserSkillInvocation(it.skill) }

    return userInvocableSkills
        .asSequence()
        .flatMap { skill -> naturalLanguageMatchesForSkill(text, skill).asSequence() }
        .filterNot { match -> isNegativeInstruction(text, match.range) }
        .minWithOrNull(compareBy<UserSkillInvocationMatch> { it.range.first }.thenBy { it.skill.name })
        ?.let { UserSkillInvocation(it.skill) }
}

private data class UserSkillInvocationMatch(
    val skill: Skill,
    val range: IntRange,
)

private fun slashMatches(
    text: String,
    skills: List<Skill>,
): Sequence<UserSkillInvocationMatch> {
    val skillsByName = skills.associateBy { it.name.lowercase() }

    return SLASH_WORD_RE
        .findAll(text)
        .mapNotNull { match ->
            val skillName = match.groupValues[1].lowercase()
            val skill = skillsByName[skillName] ?: return@mapNotNull null

            UserSkillInvocationMatch(skill, match.range)
        }
}

private fun naturalLanguageMatchesForSkill(
    text: String,
    skill: Skill,
): List<UserSkillInvocationMatch> {
    val name = Regex.escape(skill.name)
    val pattern = naturalLanguagePattern(name)

    return pattern.findAll(text).map { match -> UserSkillInvocationMatch(skill, match.range) }.toList()
}

private fun isNegativeInstruction(
    text: String,
    activationRange: IntRange,
): Boolean {
    val beforeActivation = text.substring(0, activationRange.first)
    val negativePrefix = NEGATIVE_INSTRUCTION_PREFIX_RE.find(beforeActivation) ?: return false

    return negativePrefix.range.last + 1 == activationRange.first
}

private fun naturalLanguagePattern(name: String): Regex =
    Regex("\\b(?:activate|use|invoke|run|call)\\s+(?:skill\\s+)?(?:the\\s+)?$name(?:\\s+skill)?\\b", RegexOption.IGNORE_CASE)

private val NEGATIVE_INSTRUCTION_PREFIX_RE = Regex("(?:\\bdo\\s+not\\s+|\\bdon't\\s+|\\bdont\\s+|\\bnever\\s+)$", RegexOption.IGNORE_CASE)

private val SLASH_WORD_RE = Regex("(?<!\\S)/([A-Za-z0-9][A-Za-z0-9_-]*)")
