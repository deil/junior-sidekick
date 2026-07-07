# Workspace

Sidekick uses virtual paths when exposing local files to tools and prompts.

## Roots

`/data/session` points at the current durable chat session attachments folder under `agent.state-directory`.

`/data/skills` points at `${agent.working-directory}/data/repositories/extensions`.

`/data/global` points at `${agent.working-directory}/data/repositories/knowledge`.

`/work` points at the current thread's durable bash work directory under `${agent.working-directory}/data/workspaces/threads/<conversation-id>` and is writable.

`/data/project` points at `${agent.working-directory}/data/workspaces/projects/<channel-id>` and is writable.

## Physical Workspace Layout

`agent.working-directory` is organized by ownership:

```text
workspace/
  config/
    DESCRIPTION.md
    SOUL.md
    WORLD.md
    RULES.md
    extensions.json
    knowledge.json

  templates/
    work/
    project/

  data/
    workspaces/
      threads/<conversation-id>/
      projects/<channel-id>/

    repositories/
      extensions/<repo-name-hash>/
      knowledge/<repo-name-hash>/
```

`config/` contains operator-authored prompt/config files. `templates/` contains seed files copied into new work/project workspaces. `data/` contains Sidekick-managed runtime directories and synced Git repositories.

Global context is tenant-wide company context: stable company knowledge, policies, glossary, team map, and other shared background material. Treat it as mostly read-only ordinary work context; write paths need explicit product semantics rather than accidental tool writes.

Channel-scoped project context may be provided at `/data/project/AGENTS.md`, backed by `${agent.working-directory}/data/workspaces/projects/<channel-id>/AGENTS.md`. Only this root-level `AGENTS.md` is read. When present, `SystemPromptBuilder` embeds it in the system prompt under `# Project context` for turns in that channel.

System prompt overlay files live in `${agent.working-directory}/config`: `SOUL.md` becomes `# Personality`, `WORLD.md` becomes `# World`, and `RULES.md` becomes `# Operating rules`. Slack App Home reads `${agent.working-directory}/config/DESCRIPTION.md`.

## Global Repositories

Sidekick discovers knowledge repositories from `${agent.working-directory}/config/knowledge.json`.

Configuration shape:

```json
{
  "knowledge": [
    {
      "url": "git@github.com:deil/global-context.git",
      "path": "docs",
      "sshKeyPath": "/home/sidekick/.ssh/global"
    }
  ]
}
```

Missing `knowledge.json` and empty `knowledge` lists are no-ops.

Configured repositories are cloned or refreshed under `${agent.working-directory}/data/repositories/knowledge/` with stable repository-name plus URL-hash checkout naming.

## Resolution

`parseVirtualPath()` resolves supported virtual roots against the current session attachments root, configured extension repository root, configured knowledge repository root, session work root, and project root. Callers should pass virtual paths across model/tool boundaries instead of absolute filesystem paths.

Filesystem tools must reject path escape and symbolic links. Do not use `FOLLOW_LINKS`; resolve and walk paths with no-follow semantics so virtual roots cannot be bypassed through symlinked files or directories.

## Key files

- [WorkspaceLayout.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/workspace/WorkspaceLayout.kt) - Physical `agent.working-directory` folder and config-file layout.
- [VirtualPaths.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/workspace/VirtualPaths.kt) - Virtual path helpers and root resolution.
- [GlobalWorkspace.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/workspace/global/GlobalWorkspace.kt) - `knowledge.json` loading and knowledge repository sync.
- [GitRepositories.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/git/GitRepositories.kt) - Shared JGit checkout path, clone, fetch, reset, and SSH-key wiring.
- [Config.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/Config.kt) - Raw agent configuration and `WorkspaceLayout` construction.
