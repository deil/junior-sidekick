package com.github.uncomplexco.example

import com.github.uncomplexco.sidekick.EnableSidekick
import com.github.uncomplexco.sidekick.dumphere.DumpHereToolProvider
import com.github.uncomplexco.sidekick.tools.SidekickToolProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@EnableSidekick
@SpringBootApplication
class SidekickBotApp {
    @Bean
    fun dumpHereTools(
        @Value($$"${integrations.dumphere.base-url}") baseUrl: String,
        @Value($$"${integrations.dumphere.username}") username: String,
        @Value($$"${integrations.dumphere.password}") password: String,
    ): SidekickToolProvider = DumpHereToolProvider(baseUrl, username, password)
}

fun main(args: Array<String>) {
    runApplication<SidekickBotApp>(*args)
}
