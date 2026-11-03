---
type: Domain
---

# Project Context

Project context is the resolved project-wide information carried into an execution.
It contains tags and instructions; callers consume the result independently of how
that information was obtained. Projects currently map to chat channels through their
[workspace project roots](workspace.md#roots).

```kotlin
data class ProjectContext(
    val tags: Set<String> = emptySet(),
    val instructions: String? = null,
)
```

## Resolution

`ProjectContexts.resolve(projectRoot)` owns source loading and parsing. Its current
source is the root-level `AGENTS.md` in that project workspace:

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
Trailing instruction whitespace is trimmed.

Malformed frontmatter and invalid tag types surface as configuration errors. The
resolver separates metadata from instructions so configuration stays outside model
instruction text.

The shared `parseMarkdownFrontmatter` and `parseYaml` helpers own YAML parsing and
document boundaries. Project resolution validates the project-specific tag list;
skill and subagent consumers use the same helpers for their metadata and instruction bodies.

## Execution Snapshot

When recording the initial incoming batch, `SessionManager` resolves project context
from the prepared project workspace and stores it in `ConversationContext.project`.
That snapshot remains stable through steering and child execution-context copies.
Each new turn resolves the current source again.

```text
project sources → ProjectContexts → ProjectContext
                                       ↓
                              ConversationContext.project
                                       ↓
                             Sidekick system prompt
```

`SystemPromptBuilder` renders resolved instructions under `# Project context`, between
world context and operating rules. Project tags select the [skills catalog](skills.md)
used by Sidekick and Tasks, including skill activation by name. See
[context management](context-management.md#prompt-shape) for prompt composition,
[subagents](subagents.md#prompt-generation) for Task prompting, and
[turn flow](turn-flow.md) for the execution lifecycle.
