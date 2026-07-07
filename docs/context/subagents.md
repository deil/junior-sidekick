# Subagents

Sidekick exposes a Koog `Task` tool that runs an isolated subagent and returns the subagent's final answer to the parent agent.

## Task Tool Contract

`Task` accepts:

- `description` - short status label shown while the subagent runs.
- `prompt` - full instruction passed as the subagent's only user message.
- `subagent_type` - subagent name, defaulting to `general`.

While the tool runs, Sidekick reports status as `<subagent_type> task - <description>`, then restores generic thinking status when the call completes.

The `Task` tool descriptor is built dynamically from the agents catalog. Its description ends with:

```text
Available agent types and the tools they have access to:
  - general: general-purpose agent for complex questions and multi-step tasks
```

## Agent Definitions

Subagent system prompts come from the agents catalog. Built-in definitions live as application resources under `tools/src/main/resources/agents/`.

For a built-in `subagent_type` named `general`, Sidekick loads:

```text
tools/src/main/resources/agents/general.md
```

Sidekick also discovers extension subagents from the same configured extension repositories used for skills. After the extensions have been cloned/refreshed under `${agent.working-directory}/data/repositories/extensions`, the agents catalog scans each configured repository path for:

```text
<checkout>/<path>/agents/*.md
```

When `path` is omitted or blank, Sidekick scans:

```text
<checkout>/agents/*.md
```

Agent definition files must be Markdown files with frontmatter containing `description`. `name` defaults to the file basename if omitted. If present, `name` must match the file basename. Names must use lowercase letters, numbers, and single hyphens.

Sidekick strips the first frontmatter block before using the Markdown body as the system prompt. Invalid extension agent files are skipped. Built-in agents win name collisions with extension agents.

Current built-in agent:

- `general` - general-purpose autonomous subagent.

## Runtime Isolation

Subagents use the configured normal/default Koog model profile and the global max agent iteration setting. They do not inherit Sidekick chat memory or session history; the `prompt` argument is the only user message.

The subagent tool registry contains only read/search/web tools:

- `Read`
- `Glob`
- `Grep`
- `webFetch`

The file tools use the parent turn's same virtual paths.

## Key Files

- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/subagents/Subagents.kt` - built-in and extension subagent loading and frontmatter stripping.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/subagents/TaskTool.kt` - Koog `Task` tool descriptor, validation, status updates, and runner call.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/subagents/KoogSubagentRunner.kt` - isolated Koog agent construction and subagent tool registry.
