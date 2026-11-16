package com.github.uncomplexco.sidekick.application.chat

data class TodoProgress(val text: String, val statusLine: String) {
    fun render(): String = "$text\n\n$statusLine"
}

data class SubagentProgress(
    val id: String,
    val description: String,
    val type: String,
    val status: SubagentStatus,
)

enum class SubagentStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
}
