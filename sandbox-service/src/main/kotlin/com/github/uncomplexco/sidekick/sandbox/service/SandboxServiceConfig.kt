package com.github.uncomplexco.sidekick.sandbox.service

import io.ktor.server.config.ApplicationConfig
import java.nio.file.Path

data class SandboxServiceConfig(
    val port: Int = 8080,
    val token: String,
    val bwrapPath: String = "bwrap",
    val rootfs: Path,
    val maxOutputBytes: Int = 50 * 1024,
    val uid: Int = 65534,
    val gid: Int = 65534,
    val allowedSourcePrefixes: List<Path>,
) {
    companion object {
        fun fromApplicationConfig(config: ApplicationConfig): SandboxServiceConfig =
            SandboxServiceConfig(
                port = config.optionalInt("ktor.deployment.port") ?: 8080,
                token = config.requiredString("sandbox.token"),
                bwrapPath = config.optionalString("sandbox.bwrap-path") ?: "bwrap",
                rootfs = Path.of(config.requiredString("sandbox.rootfs")),
                maxOutputBytes = config.optionalInt("sandbox.max-output-bytes") ?: 50 * 1024,
                uid = config.optionalInt("sandbox.uid") ?: 65534,
                gid = config.optionalInt("sandbox.gid") ?: 65534,
                allowedSourcePrefixes =
                    config
                        .propertyOrNull("sandbox.allowed-source-prefixes")
                        ?.getList()
                        ?.map { Path.of(it) }
                        ?: emptyList(),
            )
    }
}

private fun ApplicationConfig.requiredString(path: String): String =
    optionalString(path)?.takeIf { it.isNotBlank() } ?: error("Missing required config: $path")

private fun ApplicationConfig.optionalString(path: String): String? =
    propertyOrNull(path)?.getString()

private fun ApplicationConfig.optionalInt(path: String): Int? =
    propertyOrNull(path)?.getString()?.toInt()
