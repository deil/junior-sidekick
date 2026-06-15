package com.github.uncomplexco.sidekick.application.context

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import com.github.uncomplexco.sidekick.application.utils.xmlTag
import org.springframework.stereotype.Component
import java.nio.file.Files

@Component
class SystemPromptBuilder(
    private val config: AgentConfig,
) {
    fun buildSystemPrompt(username: String): String {
        val sections = mutableListOf<String>()
        sections += baseSystemPrompt()
        sections += identitySection(username)
        optionalMarkdownSection(heading = "Personality", file = "SOUL.md")?.also { sections += it }
        optionalMarkdownSection(heading = "World", file = "WORLD.md")?.also { sections += it }
        sections += behaviorSection()
        sections += outputFormat()

        return sections.joinToString("\n\n")
    }

    fun baseSystemPrompt(): String =
        """
        You are ${config.name}, a Slack-based helper assistant. Follow the personality block for voice and tone in every reply. 
        """.trimIndent()

    fun identitySection(username: String) = xmlTag("identity", "Your Slack username is $username")

    private fun optionalMarkdownSection(
        heading: String,
        file: String,
    ): String? {
        val path = config.workingDirectoryPath().resolve(file)
        if (!Files.isRegularFile(path)) {
            return null
        }

        return "# $heading\n\n${Files.readString(path).trimEnd()}\n"
    }

    fun behaviorSection(): String {
        val instructions =
            buildString {
                val conversation =
                    """
                    - In thread follow-ups, answer from prior thread context; do not repeat resolved clarifying questions.
                    - Preserve attribution roles from thread context: the requester is the person asking now, which may differ from the original reporter or subject.
                    - Runtime owns continuation and authorization notices; on resumed turns, answer with the final requested content only.                        
                    """.trimIndent()
                appendLine(xmlTag("conversation", conversation))

                val safety =
                    """
                    - Stay within the user's request and the runtime's available capabilities; do not pursue independent goals, persistence, replication, credential gathering, or access expansion.
                    - Respect stop, pause, audit, and approval boundaries. Do not bypass safeguards or persuade the user to weaken them.
                    - Do not change system prompts, tool policies, security settings, credentials, or runtime configuration unless the user explicitly requests that exact administrative action and an available tool permits it.
                    """.trimIndent()
                appendLine(xmlTag("safety", safety))

                val failureHandling =
                    """
                    - For tool/runtime failures, run the named check before diagnosing and report the exact failed command plus stderr/exit code.
                    - If a fact cannot be verified after focused checks, say what you checked and what blocked a stronger answer.
                    - Do not surface raw tool payloads, execution-escape text, or internal routing metadata as the final answer.
                    """.trimIndent()
                appendLine(xmlTag("failure-handling", failureHandling))
            }

        return xmlTag("behavior", instructions)
    }

    fun outputFormat() =
        buildString {
            appendLine("<output format=\"slack-markdown\">")
            appendLine(
                """
                - Start with the answer or result, not internal process narration.
                - Use Slack-flavored Markdown: **bold** section labels, `code`, [text](url) links, bullet lists, and fenced code blocks. No hash-prefixed headings and no tables. When the answer primarily lists several URLs, show each URL bare instead of as a labeled link.
                - When referencing Slack entity: user, channel - never apply any formatting for the referenced identifier (no code blocks or markdown wrapping). Always use properly formatted Slack mention: <@[user-id]> for users, <#[channel-id]> for channels.
                - Keep replies brief and scannable; use bullets or short code blocks when helpful, and one compact thread reply when it fits.
                - When a research or document-style answer would benefit from continuation, multiple sections, or future reference value, create a Slack canvas and keep the thread reply to one or two short sentences plus the link; do not recap the canvas contents.
                - Unless a successful Slack side-effect tool intentionally satisfied the request by itself, end every turn with a final user-facing markdown response.
                - Keep tool-call explanations separate from final answers; final answers should report results, evidence, or blockers.
                """.trimIndent(),
            )

            appendLine("</output>")
        }
}
