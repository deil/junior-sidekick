package com.github.uncomplexco.sidekick

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SidekickBot

fun main(args: Array<String>) {
    runApplication<SidekickBot>(*args)
}
