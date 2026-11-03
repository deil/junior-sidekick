# Context Map

Hierarchical index of project context documentation for future-us and AI agents.

**Format:** Each entry has a 1-sentence summary: key concepts, keywords, tech; ends with "read for X" to indicate when to consult.

---

## Core. Do not proceed until you read these files.

- [terminology.md](terminology.md) – Core project terms for Sidekick conversations, chat platforms, sessions, and turns; read for naming alignment.
- [practices.md](practices.md) – Core architectural and implementation practices for Kotlin/Spring backend code, chat adapters, use cases, sessions, turns, and tests; read for coding patterns.

## Domain Specs. Read these files based on relevance to current task or user's request.

- [project-context.md](project-context.md) – Resolved project tags and instructions, AGENTS.md source parsing, and per-turn context snapshots; read for project context loading and consumption changes.
- [conversation-management.md](conversation-management.md) – Conversation trigger flow, Slack thread seeding, session history ownership, prompt context shape, and deduplication invariants; read for conversation behavior changes.
- [context-management.md](context-management.md) – Sidekick system/user prompt assembly, bootstrap versus follow-up context, and session compaction invariants; read for prompt generation, context window, and prompt history changes.
- [mcp.md](mcp.md) – Koog MCP tool registry configuration, supported transports, Grafana MCP examples, and registry merge behavior; read for MCP tool integration changes.
- [slack.md](slack.md) – Slack channel and thread identifier semantics, including `C`/`G`/`D` prefixes and DM channel handling; read for Slack adapter mapping changes.
- [skills.md](skills.md) – Skill sources, discovery, activation, and orchestrator catalog prompting; read for skills loading, prompting, and activation changes.
- [subagents.md](subagents.md) – `Task` behavior, subagent system/user prompt shape, definition loading, context isolation, and tool access; read for subagent execution and prompt generation changes.
- [turn-flow.md](turn-flow.md) – Session/Turn processing model, turn boundary, and processing stages from Slack event through turn participation and response delivery; read for turn orchestration and participation changes.
- [turn-suspension.md](turn-suspension.md) – Durable turn checkpoint/resume semantics, external-action waiting, supersession, races, parallel tools, and Koog Persistence versus ChatMemory; read for suspended turns and per-user OAuth continuation work.
- [scheduled-jobs.md](scheduled-jobs.md) – Channel-scoped recurring prompts, conversational management, isolated scheduled runs, persistence, and delivery; read for scheduled agent work.
- [workspace.md](workspace.md) – Virtual path roots for session, skills, and global context storage; read for file storage and workspace path changes.
- [bash.md](bash.md) – Native bash tool sandboxing with Bubblewrap, rootfs configuration, scratch mounts, network toggle, and non-root execution; read for shell/tool execution changes.
