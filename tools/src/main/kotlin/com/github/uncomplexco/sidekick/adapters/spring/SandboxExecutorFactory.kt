package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.adapters.bwrap.BwrapSandboxExecutor
import com.github.uncomplexco.sidekick.adapters.http.SandboxV2Client
import com.github.uncomplexco.sidekick.adapters.sandbox.RemoteSandboxV2Executor
import com.github.uncomplexco.sidekick.adapters.sandbox.SameHostSandboxExecutor
import com.github.uncomplexco.sidekick.application.agent.workspace.VirtualPaths
import com.github.uncomplexco.sidekick.ports.sandbox.SandboxExecutor
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandbox
import com.github.uncomplexco.sidekick.sandbox.bwrap.BwrapSandboxConfig
import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
class SandboxExecutorFactory(
    private val config: SandboxExecutorConfig,
    private val v2Client: SandboxV2Client = SandboxV2Client(config.http.baseUrl, config.http.token),
) {
    fun create(virtualPaths: VirtualPaths? = null): SandboxExecutor =
        when (config.provider.trim().lowercase()) {
            "bwrap" -> withWorkspace(bwrapExecutor(), virtualPaths)
            "http" -> withWorkspace(httpExecutor(), virtualPaths)
            "http-v2" -> {
                check(config.http.baseUrl.isNotBlank()) {
                    "Bash sandbox HTTP base URL is not configured"
                }
                check(config.http.token.isNotBlank()) {
                    "Bash sandbox HTTP token is not configured"
                }

                RemoteSandboxV2Executor(v2Client)
            }
            else -> error("Unsupported bash sandbox provider: ${config.provider}")
        }

    private fun withWorkspace(
        executor: SandboxExecutor,
        virtualPaths: VirtualPaths?,
    ): SandboxExecutor =
        if (virtualPaths == null) executor
        else WorkspaceSandboxExecutor(virtualPaths, config.scratchGid, executor)

    private fun httpExecutor(): SandboxExecutor {
        val http = config.http
        if (http.baseUrl.isBlank()) {
            error("Bash sandbox HTTP base URL is not configured")
        }
        if (http.token.isBlank()) {
            error("Bash sandbox HTTP token is not configured")
        }
        return SameHostSandboxExecutor(
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
                )
            )
        )
    }
}

@Component
@ConfigurationProperties(prefix = "agent.tools.bash")
class SandboxExecutorConfig {
    var provider: String = "http"
    var scratchGid: Int? = null
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
