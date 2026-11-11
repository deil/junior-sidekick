package com.github.uncomplexco.sidekick.adapters.jgit

data class LocalGitCredentials(
    val sshKeyFile: String? = null,
    val githubSshKeyFile: String? = null,
    val bitbucketSshKeyFile: String? = null,
) {
    internal fun sshKeyFile(provider: GitProvider): String {
        val (value, property) =
            when (provider) {
                GitProvider.GITHUB -> githubSshKeyFile to "agent.tools.git.github.ssh-key-file"
                GitProvider.BITBUCKET ->
                    bitbucketSshKeyFile to "agent.tools.git.bitbucket.ssh-key-file"
                GitProvider.AZURE_DEVOPS,
                GitProvider.OTHER -> sshKeyFile to "agent.tools.git.ssh-key-file"
            }

        return value?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "${provider.displayName} SSH key is not configured: $property"
            )
    }
}
