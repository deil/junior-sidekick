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
[Project context](project-context.md) is loaded once per turn; its instructions appear
under `# Project context`.
Optional personality, world, and operating-rule files contribute sections when present.
Prompt construction belongs to the caller, not the shared agent runtime.

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

Compaction summarizes older messages while preserving the recent live transcript.

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

Compaction never removes all live messages. The latest `MIN_LIVE_MESSAGES` remain
untouched in the live transcript. A session with compaction summaries but no live
history is invalid for normal prompting.

Compaction runs only when recording a new incoming message, after that message is added to session state and before the state is saved or used to build the turn context.

Assistant replies are not a compaction trigger.

### Parameters

- `MIN_LIVE_MESSAGES`: number of most recent live messages that must remain untouched.
- `COMPACTION_TRIGGER_TOKENS`: estimated prompt-context size that starts compaction.
- `COMPACTION_TARGET_TOKENS`: lower prompt-context size to reach once compaction starts.
- `COMPACTION_BATCH_SIZE`: maximum number of oldest live messages summarized in one iteration.

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
  append summary with covered message IDs and assistant-message count
  remove covered messages from the live transcript

  re-estimate prompt context
```

Compaction starts above the trigger threshold and continues toward the lower target
threshold, rather than stopping as soon as context drops below the trigger.

### Token estimation

Prompt-context size is estimated at roughly one token per four characters.

## Key files

- [SystemPromptBuilder.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/SystemPromptBuilder.kt) and [TurnPromptBuilder.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/TurnPromptBuilder.kt) – Build the system prompt, thread context, compaction blocks, transcript, and current-message prompt structure.
- [Compaction.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/context/Compaction.kt) – Owns context-size estimation, compaction trigger hysteresis, batch rewriting, and summarization fallback.
- [Models.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/Models.kt) – Defines persisted session messages, compactions, and in-flight state.
