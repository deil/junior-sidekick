# Context Map

Hierarchical index of project context documentation for future-us and AI agents.

**Format:** Each entry has a 1-sentence summary: key concepts, keywords, tech; ends with "read for X" to indicate when to consult.

---

## Core. Do not proceed until you read these files.

- [terminology.md](terminology.md) – Core project terms for Sidekick conversations, chat platforms, sessions, and turns; read for naming alignment.
- [practices.md](practices.md) – Core architectural and implementation practices for Kotlin/Spring backend code, chat adapters, use cases, sessions, turns, and tests; read for coding patterns.

## Domain Specs. Read these files based on relevance to current task or user's request.

- [conversation-management.md](conversation-management.md) – Conversation trigger flow, Slack thread seeding, session history ownership, prompt context shape, and deduplication invariants; read for conversation behavior changes.
- [context-management.md](context-management.md) – Session context compaction and prompt-context invariants, including the requirement that live recent messages always remain; read for context window and prompt history changes.
- [mcp.md](mcp.md) – Koog MCP tool registry configuration, supported transports, Grafana MCP examples, and registry merge behavior; read for MCP tool integration changes.
- [slack.md](slack.md) – Slack channel and thread identifier semantics, including `C`/`G`/`D` prefixes and DM channel handling; read for Slack adapter mapping changes.
- [skills.md](skills.md) – Remote extension repository configuration, checkout layout, skill discovery metadata, validation, and current scope; read for skills loading and activation changes.
- [turn-flow.md](turn-flow.md) – Session/Turn processing model, turn boundary, and processing stages from Slack event to reply delivery; read for turn orchestration and trigger/reply-policy changes.
- [workspace.md](workspace.md) – Virtual path roots for session, skills, and global context storage; read for file storage and workspace path changes.
- [bash.md](bash.md) – Native bash tool sandboxing with Bubblewrap, rootfs configuration, scratch mounts, network toggle, and non-root execution; read for shell/tool execution changes.
