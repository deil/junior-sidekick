package com.github.uncomplexco.sidekick.adapters.files

import com.github.uncomplexco.sidekick.adapters.http.SandboxV2Client
import com.github.uncomplexco.sidekick.adapters.sandbox.RemoteWorkspaceFilesystem
import com.github.uncomplexco.sidekick.adapters.spring.SandboxExecutorConfig
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class WorkspaceFilesystemConfiguration {
    @Bean
    fun sandboxV2Client(sandbox: SandboxExecutorConfig): SandboxV2Client =
        SandboxV2Client(sandbox.http.baseUrl, sandbox.http.token)

    @Bean
    fun workspaceFilesystemFactory(
        bash: BashToolConfig,
        sandbox: SandboxExecutorConfig,
        client: SandboxV2Client,
    ): WorkspaceFilesystemFactory {
        if (!bash.enabled) return WorkspaceFilesystemFactory()

        return when (sandbox.provider.trim().lowercase()) {
            "http-v2" -> WorkspaceFilesystemFactory { RemoteWorkspaceFilesystem(client) }
            "http",
            "bwrap" -> WorkspaceFilesystemFactory()
            else -> error("Unsupported bash sandbox provider: ${sandbox.provider}")
        }
    }
}
