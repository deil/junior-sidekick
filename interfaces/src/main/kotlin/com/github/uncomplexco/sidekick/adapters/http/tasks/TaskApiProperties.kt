package com.github.uncomplexco.sidekick.adapters.http.tasks

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("task-api.auth")
data class TaskApiProperties(val clients: Map<String, Client> = emptyMap()) {
    data class Client(val keyHashes: List<String> = emptyList())
}
