---
type: Domain
---

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

## Prompt Generation

Subagent prompting is separate from [Sidekick session prompting](context-management.md).
The selected agent definition supplies behavior, the current skills catalog supplies
skill discovery instructions, and the caller supplies the task context. Prompt composition
belongs to the subagent caller of `KoogAgent`.

The initial model-visible messages have this shape:

```text
SYSTEM
  Selected subagent definition's Markdown body, with frontmatter stripped.

  <skills>
    Activation instructions and available/user-invocable skill entries.
  </skills>

USER
  Task.prompt, unchanged.
```

`subagent_type` selects the system prompt. `description` supplies the task's progress
status label. Tool definitions are supplied separately from message text;
the tool loop then adds model tool calls and tool results before the final answer.

The `<skills>` section follows the agent instructions, separated by a blank line.
It selects the current catalog using the inherited project tags: every skill tag must
belong to the project's tag set. Untagged skills match every project. The child's virtual
paths supply skill locations. A catalog with no model-invocable or user-invocable skills leaves the
definition unchanged. Skill instructions are loaded on demand through `activateSkill`.

Each subagent starts with a fresh message history containing the system prompt and delegated
task shown above. Workspace and attachment access comes from bound tools.

The catalog section uses the shared [skill entry shape and activation rules](skills.md#turn-prompt-catalog).
Model-invocable skills are available for task-based selection; user-invocable skills
require an explicit user request. Subagent catalogs belong to the system prompt;
Sidekick session catalogs belong to the bootstrap user prompt.

## Runtime Isolation

Subagents use the configured normal/default Koog model profile and the global max agent iteration setting. Each execution starts with its own message history; the `prompt` argument supplies the initial user message.

Task receives the global catalog's fixed shared selection. Callers cannot narrow or
override it. Shared tools include workspace files, configured sandbox/bash, git, web, skills,
time utilities, stats, platform history/user/channel/file reads, and MCP/extension tools
classified as shared. Workspace and sandbox operations are not restricted to reads.

`Todo` is available to Sidekick and delegates. It returns the supplied structured
list; only Sidekick publishes the list to chat. Delegate calls have no delivery side effects.

Skill catalog reloading, user-facing status, attachments, canvases, reactions, interactive authentication, session
settings, scheduling, and delegation tools are orchestrator-only. Delegates return results
to their caller without independently communicating with the requester.

Each Task has its own execution context and tool instances, using the parent's virtual paths
and current attachment context.
Task generates a fresh agent ID and run ID for each invocation and executes through
`KoogAgent.run()`. The run ID is also the child execution context's `turnId`.
The conversation ID is inherited; OpenRouter session grouping keeps
the shared runtime's conversation-prefixed session ID. Run IDs are reserved for
persistence/checkpoints. Completed and failed runs both contribute reported usage to the parent;
cancellation propagates.
MCP connections remain owned by the parent turn; delegates bind fresh wrappers from those
execution-local sources. Tools and live connections are never global singletons.

## Key Files

- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/subagents/Subagents.kt` - built-in and extension subagent loading and frontmatter stripping.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/subagents/TaskTool.kt` - Koog `Task` tool descriptor, validation, status updates, and runner call.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/subagents/KoogSubagentRunner.kt` - subagent prompt selection and execution through the shared Koog runtime using catalog-bound tools.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/agent/tools/AgentToolCatalog.kt` - registration, access policy, and execution binding contracts.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/DefaultAgentToolCatalog.kt` - built-in and extension registrations, MCP source binding, and fixed orchestrator/delegate binding methods.
