---
type: Domain
---

# Context Management

Focused spec for how Sidekick prepares persisted session context for LLM turns.
See [turn flow](turn-flow.md) for the surrounding admission and execution lifecycle.

## Prompt shape

Sidekick session prompts and [delegated Task prompts](subagents.md#prompt-generation)
have separate construction contracts. This section describes Sidekick session prompts.

`SystemPromptBuilder` assembles the system prompt in this order: base platform-specific
behavior, username identity, optional personality (`SOUL.md`), world (`WORLD.md`),
resolved project instructions, and operating rules (`RULES.md`).
[Project context](project-context.md) is loaded once for the turn and supplied through
`ConversationContext.project`; the builder renders its instructions under `# Project context`.
Optional personality, world, and operating-rule files contribute sections when present.
Prompt construction belongs to the caller, not the shared agent runtime.

### Markdown templates

`SOUL.md`, `WORLD.md`, `RULES.md`, and the Markdown body of project `AGENTS.md`
are rendered with Pebble before their instructions enter a prompt. YAML frontmatter
is parsed separately and is not templated. Project instructions are rendered at
resolution, so the same selected instructions remain in the per-turn project snapshot.

```twig
{% if features.chat_ui %}
Use rich chat cards for tables and visualizations.
{% else %}
Use plain-text lists. Do not emit fenced spec blocks.
{% endif %}
```

`features.chat_ui` is the boolean Spring property `agent.chat-ui.enabled` (default
`false`). Future flags must be explicitly added to the template context; templates
cannot read the Spring environment, arbitrary properties, or application beans.
`RULES.md` also receives `NO_REPLY_MARKER`, with existing `{{NO_REPLY_MARKER}}`
references handled by Pebble. Markdown is not HTML-escaped. Unknown variables and
syntax errors fail with the source file in the error message. Use Pebble's
`{% verbatim %}...{% endverbatim %}` when documenting literal template syntax.

Templates render from their loaded text, with no filesystem template loader or
compiled-template cache. Skill definitions, subagent definitions, user messages,
and stored conversation history are not templated by this mechanism. Configuration
changes require a restart; Markdown edits are picked up on the next prompt build.

`TurnPromptBuilder` creates one user message per incoming message in the admitted batch.
Only the first includes shared context and requester metadata. On bootstrap, its shape is:

```text
system:
  base behavior, identity, and configured personality/world/project/rules sections

user:
<runtime_context>
...
</runtime_context>

<thread_compactions>
...
</thread_compactions>

<thread_transcript>
...
</thread_transcript>

<skills>
  <available_skills>
    ... model-invocable skills the model may choose when the task matches ...
  </available_skills>
  <user_invocable_skills>
    ... skills the user may explicitly request; not for autonomous model selection ...
  </user_invocable_skills>
</skills>

<requester>
  user_id: ...
  full_name: ...
</requester>

<explicit_skill_invocation>
The user explicitly requested this skill. Call activateSkill with this name before answering.

/example-skill
</explicit_skill_invocation>

<current_instruction>
[author username] current message text, XML-escaped
</current_instruction>

... current message's file attachments, when present ...
```

The current message is excluded from transcript history. That invariant prevents Sidekick from seeing the same user request twice in one turn.

`<skills>` appears only when bootstrapping model-visible thread context and only when at least one model-invocable or user-invocable skill exists. Its contents and activation rules are documented in [skills](skills.md#turn-prompt-catalog). `<explicit_skill_invocation>` appears for a preprocessed invocation whose skill is available and user-invocable in the project. Message text is XML-escaped and author-prefixed, not rewritten.

With existing Koog history, bootstrap runtime facts, compactions, transcript, and skills
are omitted. The first user message can instead include pending skipped messages before
requester metadata. Later messages in the batch contain their own explicit invocation,
current instruction, and attachments without repeating shared context.

Seeded Slack history affects prompts the same way locally recorded history does. Once persisted, it is just session history.

## Compaction

Compaction rewrites older live transcript into `SessionCompaction` summaries while preserving the recent live transcript as `SessionMessage` entries.

Compactions are older context, not replacement history:

```text
older messages       latest messages       current message
      |                    |                    |
      v                    v                    v
compactions.jsonl     messages.jsonl      <current_instruction>
      |                    |
      +---------> <thread_compactions>
                   <thread_transcript>
```

Invariant: compaction never removes all live messages. The latest `MIN_LIVE_MESSAGES` always remain untouched in `messages.jsonl`.

A state with compactions but no live history is invalid for normal session prompting.

The compaction algorithm belongs in a dedicated context component. The session service owns the lifecycle hook; the compactor owns the decision and rewrite algorithm.

Compaction runs only when recording a new incoming message, after that message is added to session state and before the state is saved or used to build the turn context.

Assistant replies are not a compaction trigger. They are part of the protected recent live transcript, so compacting immediately after recording one adds noise without improving prompt readiness.

### Parameters

- `MIN_LIVE_MESSAGES`: number of most recent live messages that must always remain untouched.
- `COMPACTION_TRIGGER_TOKENS`: estimated prompt-context size that starts compaction.
- `COMPACTION_TARGET_TOKENS`: estimated prompt-context size compaction tries to reach once triggered.
- `COMPACTION_BATCH_SIZE`: maximum number of oldest live messages compacted in one iteration.

### Algorithm

```text
estimate prompt context
if estimate <= COMPACTION_TRIGGER_TOKENS:
  stop

while estimate > COMPACTION_TARGET_TOKENS:
  compactable_count = live_message_count - MIN_LIVE_MESSAGES
  if compactable_count <= 0:
    stop

  batch_size = min(COMPACTION_BATCH_SIZE, compactable_count)
  batch = oldest live messages up to batch_size

  summary = summarize(batch)
  append SessionCompaction(summary, covered message ids, assistant message count)
  remove covered messages from messages.jsonl

  re-estimate prompt context
```

This hysteresis avoids compacting again immediately after every small context change.

### Token estimation

Sidekick does not use an external tokenizer for compaction decisions. Prompt-context size is estimated with a heuristic: roughly four characters are treated as one token.

## Key files

- [SystemPromptBuilder.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/SystemPromptBuilder.kt) and [TurnPromptBuilder.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/TurnPromptBuilder.kt) – Build the system prompt, thread context, compaction blocks, transcript, and current-message prompt structure.
- [Compaction.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/Compaction.kt) – Owns context-size estimation, compaction trigger hysteresis, batch rewriting, and summarization fallback.
- [Models.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/Models.kt) – Defines persisted session messages, compactions, and in-flight state.
