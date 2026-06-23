package com.github.uncomplexco.sidekick.adapters.sandbox

import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandbox
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class SandboxExecutorFactory(
    private val config: SandboxExecutorConfig,
) {
    fun create(): SandboxExecutor =
        when (config.provider.trim().lowercase()) {
            "bwrap" -> bwrapExecutor()
            "http" -> httpExecutor()
            else -> error("Unsupported bash sandbox provider: ${config.provider}")
        }

    private fun httpExecutor(): SandboxExecutor {
        val http = config.http
        if (http.baseUrl.isBlank()) {
            error("Bash sandbox HTTP base URL is not configured")
        }
        if (http.token.isBlank()) {
            error("Bash sandbox HTTP token is not configured")
        }
        return HttpSandboxExecutor(
            baseUrl = http.baseUrl,
            token = http.token,
        )
    }

    private fun bwrapExecutor(): SandboxExecutor {
        val bwrap = config.bwrap
        if (bwrap.rootfs.isBlank()) {
            error("Bash sandbox bwrap rootfs is not configured")
        }
        return BwrapSandboxExecutor(
            BwrapSandbox(
                BwrapSandboxConfig(
                    bwrapPath = bwrap.path,
                    rootfs = Path.of(bwrap.rootfs),
                    maxOutputBytes = bwrap.maxOutputBytes,
                    uid = bwrap.uid,
                    gid = bwrap.gid,
                ),
            ),
        )
    }
}

@Component
@ConfigurationProperties(prefix = "agent.tools.bash")
class SandboxExecutorConfig {
    var provider: String = "http"
    var http: HttpProviderConfig = HttpProviderConfig()
    var bwrap: BwrapProviderConfig = BwrapProviderConfig()
}

class HttpProviderConfig {
    var baseUrl: String = ""
    var token: String = ""
}

class BwrapProviderConfig {
    var path: String = "bwrap"
    var rootfs: String = ""
    var maxOutputBytes: Int = 50 * 1024
    var uid: Int = 65534
    var gid: Int = 65534
}
