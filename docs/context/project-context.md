---
type: Domain
---

# Project Context

Project context contains project-wide tags and instructions. Projects map to chat channels through their
[workspace project roots](workspace.md#roots).

## Resolution

Project context comes from the root-level `AGENTS.md` in the project workspace:

```markdown
---
tags:
  - engineering
  - production
  - productA
---
Use Product A's production engineering conventions.
```

YAML frontmatter supplies an optional list of string tags; the Markdown body supplies
instructions. Inline YAML lists are also supported. Tags retain their case and are
deduplicated into a set. Other metadata is ignored. Plain Markdown supplies instructions
with an empty tag set. A missing file resolves to empty tags and absent instructions.

Malformed frontmatter, invalid tag types, and invalid templates produce a
project-configuration error without changing session history. Frontmatter metadata
stays outside model instruction text.

## Execution Snapshot

Project context is resolved after admission, before preparing attachments,
compacting history, recording messages, or deciding participation. Scheduled turns
use the same order. A configuration error rejects the turn before those operations
can change session state.

The resolved tags and instructions form one snapshot for the turn. That snapshot
stays fixed through steering and child execution, even if the source file changes
while the turn is running. Each new turn resolves the source again.

Resolved instructions appear under `# Project context`, between
world context and operating rules. Project tags select the [skills catalog](skills.md)
used by Sidekick and Tasks, including skill activation by name. See
[context management](context-management.md#prompt-shape) for prompt composition,
[subagents](subagents.md#prompt-generation) for Task prompting, and
[turn flow](turn-flow.md) for the execution lifecycle.
