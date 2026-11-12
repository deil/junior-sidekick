# Context Map

Hierarchical index of project context documentation for future-us and AI agents.

**Format:** Each entry has a 1-sentence summary: key concepts, keywords, tech; ends with "read for X" to indicate when to consult.

---

## Core. Do not proceed until you read these files.

- [terminology.md](terminology.md) – Core project terms for Sidekick conversations, chat platforms, sessions, and turns; read for naming alignment.
- [practices.md](practices.md) – Core architectural and implementation practices for Kotlin/Spring backend code, chat adapters, use cases, sessions, turns, and tests; read for coding patterns.

## Domain Specs. Read these files based on relevance to current task or user's request.

- Interfaces
  - [interfaces/web-authentication.md](interfaces/web-authentication.md) – Google sign-in, opaque bearer tokens, browser-session cookie scope, and the shared fixed 24-hour JWT/cookie expiry; read for web authentication and login persistence changes.
  - [interfaces/public-mcp.md](interfaces/public-mcp.md) – Draft inbound MCP interface, authenticated-user identity, project scope, and session boundaries; read for exposing Sidekick to other agents.
  - Slack
    - [interfaces/slack/slack.md](interfaces/slack/slack.md) – Slack channel and thread identifier semantics, including `C`/`G`/`D` prefixes and DM channel handling; read for Slack adapter mapping changes.
    - [interfaces/slack/access-context.md](interfaces/slack/access-context.md) – Draft home-organization, author-membership, conversation-sharing, and authorization boundaries; read for Slack access-control design.
  - Discord
    - [interfaces/discord/discord.md](interfaces/discord/discord.md) – Discord todo message delivery, editing, minute refreshes, and reply-target routing; read for Discord progress-message changes.

- [project-context.md](project-context.md) – Resolved project tags and instructions, AGENTS.md source parsing, and per-turn context snapshots; read for project context loading and consumption changes.
- [conversation-management.md](conversation-management.md) – Conversation trigger flow, Slack thread seeding, session history ownership, prompt context shape, and deduplication invariants; read for conversation behavior changes.
- [context-management.md](context-management.md) – Sidekick system/user prompt assembly, bootstrap versus follow-up context, and session compaction invariants; read for prompt generation, context window, and prompt history changes.
- Tools
  - [tools/mcp.md](tools/mcp.md) – Koog MCP tool registry configuration, supported transports, Grafana MCP examples, and registry merge behavior; read for MCP tool integration changes.
  - [tools/skills.md](tools/skills.md) – Skill sources, discovery, activation, and orchestrator catalog prompting; read for skills loading, prompting, and activation changes.
  - [tools/subagents.md](tools/subagents.md) – `Task` behavior, subagent system/user prompt shape, definition loading, context isolation, and tool access; read for subagent execution and prompt generation changes.
  - [tools/automations.md](tools/automations.md) – Project-owned instructions and triggers, SQLite persistence, lazy legacy import, isolated automated runs, and delivery; read for automation management and scheduled work.
- [turn-flow.md](turn-flow.md) – Session/Turn processing model, turn boundary, and processing stages from Slack event through turn participation and response delivery; read for turn orchestration and participation changes.
- [turn-suspension.md](turn-suspension.md) – Durable turn checkpoint/resume semantics, external-action waiting, supersession, races, parallel tools, and Koog Persistence versus ChatMemory; read for suspended turns and per-user OAuth continuation work.
- Sandbox
  - [sandbox/bash.md](sandbox/bash.md) – Bash execution through legacy Bubblewrap or the project-user v2 HTTP API, rootfs configuration, mounts, and the privileged Node.js helper; read for shell/tool execution changes.
  - [sandbox/workspace.md](sandbox/workspace.md) – Virtual roots, physical storage, templates, and knowledge repositories; read for file storage and workspace path changes.
  - [sandbox/virtual-filesystem.md](sandbox/virtual-filesystem.md) – Local virtual filesystem tree and root contents; read for the agent-visible local path layout.
  - [sandbox/sandbox-v2-filesystem.md](sandbox/sandbox-v2-filesystem.md) – V2 filesystem implementation status, VM paths, project templates, shared repositories, credential boundaries, and migration decisions; read for remote workspace design and implementation.

## Exploratory notes

- [thoughts/](../thoughts/) – Sketches and ideas, not necessarily implemented or fully designed; read for exploratory context.
