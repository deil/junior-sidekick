package com.github.uncomplexco.sidekick.application.tools.automations

import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.github.uncomplexco.sidekick.application.core.ACTIVE_AUTOMATION_LIMIT
import com.github.uncomplexco.sidekick.application.core.Automation
import com.github.uncomplexco.sidekick.application.core.AutomationService
import com.github.uncomplexco.sidekick.application.core.CreateAutomation
import com.github.uncomplexco.sidekick.application.core.UpdateAutomation
import java.time.DateTimeException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class AutomationTools(private val projectUid: String, private val automations: AutomationService) :
    ToolSet {
    @Tool("automations__create")
    @LLMDescription(
        "Create an automation. Scheduled automations run at minute precision and no more than hourly."
    )
    fun createAutomation(
        @LLMDescription("Required unique human-readable automation name") name: String,
        @LLMDescription("Optional automation description") description: String? = null,
        @LLMDescription("Instructions to execute on each run") instructions: String,
        @LLMDescription(
            "Trigger object with mandatory string type. For type scheduled, include schedule (five-field cron) and timezone (IANA timezone)."
        )
        trigger: JsonObject,
        @LLMDescription("Whether the automation is active. Defaults to true")
        enabled: Boolean = true,
    ): AutomationResult = asToolCall {
        AutomationResult(
            automations
                .create(
                    projectUid,
                    CreateAutomation(name, description, instructions, trigger, enabled),
                )
                .toView()
        )
    }

    @Tool("automations__list")
    @LLMDescription("List all automations in the current project.")
    fun listAutomations(): AutomationListResult {
        val listed = automations.list(projectUid)
        return AutomationListResult(
            listed.map(Automation::toView),
            listed.count { it.enabled },
            ACTIVE_AUTOMATION_LIMIT,
        )
    }

    @Tool("automations__update")
    @LLMDescription(
        "Update an automation by its project-local uid. Supply only changed fields. Set enabled=false to pause or true to resume. An empty description clears it. A supplied trigger replaces the entire trigger object."
    )
    fun updateAutomation(
        @LLMDescription("Project-local automation number") uid: Int,
        @LLMDescription("New unique name") name: String? = null,
        @LLMDescription("New description; empty text clears it") description: String? = null,
        @LLMDescription("New instructions") instructions: String? = null,
        @LLMDescription(
            "Replacement trigger object with mandatory type; scheduled triggers require schedule and timezone"
        )
        trigger: JsonObject? = null,
        @LLMDescription("Set false to pause or true to resume") enabled: Boolean? = null,
    ): AutomationResult = asToolCall {
        AutomationResult(
            automations
                .update(
                    projectUid,
                    uid,
                    UpdateAutomation(
                        name,
                        description,
                        description != null,
                        instructions,
                        trigger,
                        enabled,
                    ),
                )
                .toView()
        )
    }

    @Tool("automations__delete")
    @LLMDescription("Permanently delete an automation by its project-local uid.")
    fun deleteAutomation(
        @LLMDescription("Project-local automation number") uid: Int
    ): DeletedAutomationResult = asToolCall {
        val removed = automations.delete(projectUid, uid)
        DeletedAutomationResult(removed.uid, removed.name)
    }

    private fun <T> asToolCall(block: () -> T): T =
        try {
            block()
        } catch (error: ToolException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw ToolException.ValidationFailure(error.message ?: "Invalid automation request")
        } catch (error: DateTimeException) {
            throw ToolException.ValidationFailure(error.message ?: "Invalid automation timezone")
        }
}

@Serializable data class AutomationResult(val automation: AutomationView)

@Serializable
data class AutomationListResult(
    val automations: List<AutomationView>,
    @SerialName("active_count") val activeCount: Int,
    @SerialName("active_limit") val activeLimit: Int,
)

@Serializable data class DeletedAutomationResult(val uid: Int, val name: String)

@Serializable
data class AutomationView(
    val id: Long,
    val uid: Int,
    val name: String,
    val description: String?,
    val instructions: String,
    val trigger: JsonObject,
    val enabled: Boolean,
    @SerialName("last_run_at") val lastRunAt: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

private fun Automation.toView(): AutomationView =
    AutomationView(
        id,
        uid,
        name,
        description,
        instructions,
        trigger,
        enabled,
        lastRunAt?.toString(),
        createdAt.toString(),
        updatedAt.toString(),
    )
