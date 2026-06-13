# Context Management

Focused spec for how Sidekick prepares persisted session context for LLM turns.

## Prompt shape

Each Sidekick turn sends a system prompt plus a user message to the agent runtime.

The system prompt contains Sidekick's base behavioral contract. The user message contains prior session context plus the triggering message:

```text
system:
  base Sidekick behavior and response rules

user:
<runtime_context>
...
</runtime_context>

<skills>
  <available_skills>
    ... model-invocable skills the model may choose when the task matches ...
  </available_skills>
  <user_invocable_skills>
    ... skills the user may explicitly request; not for autonomous model selection ...
  </user_invocable_skills>
</skills>

<thread_compactions>
...
</thread_compactions>

<thread_transcript>
...
</thread_transcript>

<explicit_skill_invocation>
The user explicitly requested this skill. Call activateSkill with this name before answering.

/example-skill
</explicit_skill_invocation>

<current_instruction>
...
</current_instruction>
```

The current message is excluded from transcript history. That invariant prevents Sidekick from seeing the same user request twice in one turn.

`<skills>` appears only when bootstrapping model-visible thread context and only when at least one model-invocable or user-invocable skill exists. `<explicit_skill_invocation>` appears only when the current `SessionMessage` has `explicitSkillInvocation` materialized by message preprocessing. The prompt builder renders this field but does not detect invocations. The original `<current_instruction>` text is preserved unchanged.

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
