# Conversation Management

Implementation snapshot of how Sidekick is triggered, how conversation history is maintained, and the invariants that keep Slack delivery from turning into duplicated context soup.

## Trigger model

Sidekick is triggered from Slack through three user-visible paths:

- App mentions in channels or threads.
- Channel/thread messages delivered through Slack `message.*` events.
- Direct Slack assistant/DM messages.

Slack can deliver more than one event for the same user message. A channel mention may arrive as both `app_mention` and `message.channels`. Sidekick treats Slack `channel + ts` as the message identity and deduplicates before dispatching to use cases.

App mentions are always explicit turns. Plain message events are only allowed through when they are not Sidekick's own message and do not contain a Sidekick mention, because mentions are handled through the `app_mention` path.

The Slack adapter maps Slack events into `ChatConversationId`, `IncomingMessage`, and `ChatPlatformAdapter`. The queue groups messages from one chat conversation into an `IncomingBatch`; `TurnAdmission` owns the decision to handle or ignore that batch. During an active turn, pending input can join it through steering after a complete tool batch, without another participation decision. After the turn finishes, remaining queued messages become one next turn. Messages arriving after the final steering drain wait for that next batch.

## Session subscription

Sessions are subscribed by default. A deterministic unsubscribe command does not require an explicit mention. Within a batch, the latest unsubscribe request or explicit mention determines whether Sidekick unsubscribes or responds. A mention that itself requests unsubscribe is an unsubscribe request. `Unsubscribe` marks all batch messages skipped with reason `UNSUBSCRIBE_COMMAND`, sets the session unsubscribed, and delivers one out-of-band acknowledgement `Unsubscribed. Mention me to resume.`

The unsubscribe command patterns are deterministic:

- `unsubscribe`
- `stop (replying|responding|participating|watching)`
- `(mute|leave) this thread`
- `don't (reply|participate|watch)`

Unsubscribed sessions drop passive Slack thread messages in `TurnAdmission.admit()` before they become turns. Explicit mentions are still admitted and resubscribe the session unless the mention is itself an unsubscribe command.

```text
Slack event
  |
  +-- app_mention --------------------> trigger=EXPLICIT_MENTION
  |
  +-- message.*, contains Sidekick ----> ignored here; app_mention owns it
  |
  +-- message.*, bot's own message ----> ignored
  |
  +-- message.*, channel/thread -------> trigger=PASSIVE_MESSAGE
  |
  +-- Slack assistant chat ------------> trigger=ASSISTANT_MESSAGE
  |
  +-- TurnAdmission -------------> Ignore or Admit
```

## Session identity

Thread-scoped Slack events map to the Slack thread session:

```text
ConversationId(channelId, threadTs)
```

Root channel app mentions and root channel messages start a new Sidekick session keyed by the root message itself:

```text
ConversationId(channelId, messageTs)
```

That distinction matters. A root app mention has no prior thread context; Sidekick's reply creates the Slack thread. A thread event can have existing human history, so Sidekick can bootstrap local state from Slack.

Direct messages use the Slack DM channel as the session:

```text
ConversationId(dmChannelId, "")
```

```text
Root channel mention
  Slack:  channel=C123, ts=M1, thread_ts=null
  State:  ConversationId(C123, M1)
  Reply:  posted with thread_ts=M1

Thread message or thread mention
  Slack:  channel=C123, ts=M2, thread_ts=T1
  State:  ConversationId(C123, T1)
  Reply:  posted with thread_ts=T1

Direct message
  Slack:  channel=D123, ts=M3, thread_ts=null
  State:  ConversationId(D123, "")
  Reply:  posted to D123
```

## History ownership

Sidekick's persisted session state is the source of continuity after a session exists locally. Slack thread history is only bootstrap material.

Future-us: `seedHistory` is the current mechanical flag on `TurnAdmissionDecision.Admitted`. The deeper domain concept may be whether Sidekick is joining a session or continuing one; rename this when that language hardens enough to pay rent.

The seeding rule is:

```text
Seed from Slack only when the trigger decision allows seeding, local session history is empty, and the incoming conversation is thread-scoped.
```

In practice, the adapter passes a history loader on every interaction, but the loader returns Slack history only when Slack provides a `thread_ts`. `TurnAdmission` decides whether the turn may seed history; `SessionManager` decides whether local history is empty before calling the loader.

This keeps the model stable:

- Re-fetching Slack on every turn.
- Replaying old Slack messages into local state repeatedly.
- Treating Slack as the long-term source of truth after Sidekick has started managing the session.

```text
Incoming turn
  |
  +-- TurnAdmission
        |
        +-- Ignore ----------------------------> stop
        |
        +-- Admit(seedHistory=false) ----------> use local history only
        |
        +-- Admit(seedHistory=true) -----------> SessionManager checks local state
                                                  |
                                                  +-- has messages ------------> use local history
                                                  |
                                                  +-- empty + thread-scoped ---> load Slack thread history once
                                                                                  |
                                                                                  +-- convert to SessionMessage
                                                                                  +-- persist as local history
```

## Seeding behavior

Seeding covers two cases:

- Local session state is missing or empty.
- Humans were already talking in a Slack thread before mentioning Sidekick.

When seeding runs, Slack thread replies are loaded, the current triggering message is excluded, messages are sorted by Slack timestamp, converted into `SessionMessage`, and inserted before the current message is upserted.

The live batch is recorded through the normal turn path, replacing any seeded copies with matching IDs. Every current batch message is excluded from prior history in the turn context. Seeded messages become context; live batch messages become current turn input.

## Channel metadata in prompt context

For the first Koog turn in a Slack channel/thread session, Sidekick may include Slack channel metadata in the turn runtime context: channel name, channel topic, and channel description. This follows the same first-turn-only rule as the existing Slack `channel_id` and `thread_ts` runtime context; once Koog history exists, channel metadata is not repeated.

Slack channel metadata is turn-local prompt context, not persisted session state. Lookup failures, missing Slack scopes, rate limits, empty values, DMs, and multi-person DMs silently omit the metadata.

## Persisted history

Session state is stored under the configured agent state directory, not the workspace. The important files are:

- `messages.jsonl` for persisted session messages.
- `runtime.json` for conversation usage statistics and the optional active turn with its latest Koog checkpoint. Reads fall back to legacy `stats.json` when `runtime.json` does not exist; writes always use `runtime.json`.

Incoming user messages and assistant replies are both persisted. Assistant replies are normalized before storage: leading/trailing whitespace is trimmed, whitespace runs collapse to one space, and text is capped at 3200 characters.

`SessionManager` stores live session messages directly. It does not compact session history.

## Gotchas

Root channel app mentions should not seed history. There is no Slack thread yet; the root message starts the Sidekick session.

Thread app mentions, direct-message thread messages, and assistant thread messages seed if local history is empty. Passive channel thread messages do not seed; they only continue an existing local Sidekick session.

Channel message events never invite Sidekick. They may only continue an already-known thread session. If local state is gone, a passive thread message is ignored until Sidekick is mentioned again.

Slack event identity is `channel + ts`, not message text. Text parsing is not a reliable deduplication strategy.

The Slack history loader excludes its originating event using `currentTs`. `SessionManager.recordIncomingMessages` also excludes every batch message ID from prior turn history, preventing duplicate transcript and current input.

Seeded human messages may have weaker author data than live incoming messages depending on what the Slack history loader resolves. Prompt rendering must tolerate missing authors.

## Key files

- [TurnAdmission.kt](../../core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnAdmission.kt) – Batch admission and session selection for app mentions, passive messages, and assistant messages.
- [ReceiveChatMessageUsecase.kt](../../app/src/main/kotlin/com/github/uncomplexco/sidekick/usecases/ReceiveChatMessageUsecase.kt) – Queued and immediate incoming-message entry points.
