# Context Map

Hierarchical index of project context documentation for future-us and AI agents.

**Format:** Each entry has a 1-sentence summary: key concepts, keywords, tech; ends with "read for X" to indicate when to consult.

---

## Core

- [terminology.md](terminology.md) – Core project terms for Sidekick conversations, chat platforms, sessions, and turns; read for naming alignment.
- [practices.md](practices.md) – Core architectural and implementation practices for Kotlin/Spring backend code, chat adapters, use cases, sessions, turns, and tests; read for coding patterns.

## Domain Specs

- [conversation-management.md](conversation-management.md) – Conversation trigger flow, Slack thread seeding, session history ownership, prompt context shape, and deduplication invariants; read for conversation behavior changes.
- [slack.md](slack.md) – Slack channel and thread identifier semantics, including `C`/`G`/`D` prefixes and DM channel handling; read for Slack adapter mapping changes.
