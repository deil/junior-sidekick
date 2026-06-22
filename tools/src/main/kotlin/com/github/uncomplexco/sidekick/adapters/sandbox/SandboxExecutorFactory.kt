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
            "http" -> error("Bash sandbox provider 'http' is not implemented yet")
            else -> error("Unsupported bash sandbox provider: ${config.provider}")
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
    var bwrap: BwrapProviderConfig = BwrapProviderConfig()
}

class BwrapProviderConfig {
    var path: String = "bwrap"
    var rootfs: String = ""
    var maxOutputBytes: Int = 50 * 1024
    var uid: Int = 65534
    var gid: Int = 65534
}
