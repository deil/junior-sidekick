package com.github.uncomplexco.sidekick.adapters.spring

import com.github.uncomplexco.sidekick.adapters.git.WorkspaceGitFactory
import com.github.uncomplexco.sidekick.adapters.jgit.LocalGitCredentials
import com.github.uncomplexco.sidekick.adapters.jgit.LocalWorkspaceGit
import com.github.uncomplexco.sidekick.adapters.sandbox.RemoteWorkspaceGit
import com.github.uncomplexco.sidekick.application.tools.bash.BashToolConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class WorkspaceGitConfiguration {
    @Bean
    fun workspaceGitFactory(
        bash: BashToolConfig,
        sandbox: SandboxExecutorConfig,
        config: GitToolConfig,
    ): WorkspaceGitFactory {
        val credentials =
            LocalGitCredentials(
                config.sshKeyFile,
                config.github.sshKeyFile,
                config.bitbucket.sshKeyFile,
            )
        val local = WorkspaceGitFactory { LocalWorkspaceGit(credentials, it) }
        if (!bash.enabled) return local

        return when (sandbox.provider.trim().lowercase()) {
            "http-v2" -> WorkspaceGitFactory { RemoteWorkspaceGit() }
            "http",
            "bwrap" -> local
            else -> error("Unsupported bash sandbox provider: ${sandbox.provider}")
        }
    }
}
