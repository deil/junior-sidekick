package com.github.uncomplexco.sidekick.sandbox.service

import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandbox
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.log
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    sandboxServiceModule(SandboxServiceConfig.fromApplicationConfig(environment.config))
}

fun Application.sandboxServiceModule(
    config: SandboxServiceConfig,
    executor: SandboxCommandExecutor = bwrapExecutor(config),
) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
            },
        )
    }

    monitor.subscribe(ApplicationStarted) {
        log.info("Sandbox service started")
    }

    executeRoute(
        token = config.token,
        mountSourcePolicy = MountSourcePolicy(config.allowedSourcePrefixes),
        executor = executor,
    )
}

private fun bwrapExecutor(config: SandboxServiceConfig): SandboxCommandExecutor {
    val sandbox =
        BwrapSandbox(
            BwrapSandboxConfig(
                bwrapPath = config.bwrapPath,
                rootfs = config.rootfs,
                maxOutputBytes = config.maxOutputBytes,
                uid = config.uid,
                gid = config.gid,
            ),
        )
    return SandboxCommandExecutor { sandbox.execute(it) }
}
