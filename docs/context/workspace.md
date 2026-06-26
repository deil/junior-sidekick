# Workspace

Sidekick uses virtual paths when exposing local files to tools and prompts.

## Roots

`/data/session` points at the current durable chat session attachments folder under `agent.state-directory`.

`/data/skills` points at `${agent.working-directory}/skills`.

`/data/global` points at `${agent.working-directory}/global`.

Global context is tenant-wide company context: stable company knowledge, policies, glossary, team map, and other shared background material. Treat it as mostly read-only ordinary work context; write paths need explicit product semantics rather than accidental tool writes.

## Global Repositories

Sidekick discovers global workspace repositories from `${agent.working-directory}/global.json`.

Configuration shape:

```json
{
  "global": [
    {
      "url": "git@github.com:deil/global-context.git",
      "path": "docs",
      "sshKeyPath": "/home/sidekick/.ssh/global"
    }
  ]
}
```

Missing `global.json` and empty `global` lists are no-ops.

Configured repositories are cloned or refreshed under `${agent.working-directory}/global/` with the same stable repository-name plus URL-hash checkout naming used for skills.

## Resolution

`parseVirtualPath()` resolves supported virtual roots against the current session attachments root, configured skills root, configured global root, and optional work root. Callers should pass virtual paths across model/tool boundaries instead of absolute filesystem paths.

Filesystem tools must reject path escape and symbolic links. Do not use `FOLLOW_LINKS`; resolve and walk paths with no-follow semantics so virtual roots cannot be bypassed through symlinked files or directories.

## Key files

- [VirtualPaths.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/workspace/VirtualPaths.kt) - Virtual path helpers and root resolution.
- [GlobalWorkspace.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/workspace/global/GlobalWorkspace.kt) - `global.json` loading and global repository sync.
- [GitRepositories.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/git/GitRepositories.kt) - Shared JGit checkout path, clone, fetch, reset, and SSH-key wiring.
- [Config.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/Config.kt) - Agent working directory, skills directory, and global directory roots.
