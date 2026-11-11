package com.github.uncomplexco.sidekick.adapters.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "agent.tools.git")
class GitToolConfig {
    var sshKeyFile: String? = null
    var github: GitProviderConfig = GitProviderConfig()
    var bitbucket: GitProviderConfig = GitProviderConfig()
}

class GitProviderConfig {
    var sshKeyFile: String? = null
}
