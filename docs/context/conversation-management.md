# Conversation Management

Implementation snapshot of how Sidekick is triggered, how conversation history is maintained, and the invariants that keep Slack delivery from turning into duplicated context soup.

## Trigger model

Sidekick is triggered from Slack through three user-visible paths:

- App mentions in channels or threads.
- Channel/thread messages delivered through Slack `message.*` events.
- Direct Slack assistant/DM messages.

Slack can deliver more than one event for the same user message. A channel mention may arrive as both `app_mention` and `message.channels`. Sidekick treats Slack `channel + ts` as the message identity and deduplicates before dispatching to use cases.

App mentions are always explicit turns. Plain message events are only allowed through when they are not Sidekick's own message and do not contain a Sidekick mention, because mentions are handled through the `app_mention` path.

The Slack adapter maps Slack events into `ChatConversationId`, `IncomingChatMessage`, and `ChatPlatformAdapter`. `ConversationTriggerPolicy` owns the decision to handle or ignore the message.

```text
Slack event
  |
  +-- app_mention --------------------> trigger=APP_MENTION
  |
  +-- message.*, contains Sidekick ----> ignored here; app_mention owns it
  |
  +-- message.*, bot's own message ----> ignored
  |
  +-- message.*, channel/thread -------> trigger=PASSIVE_MESSAGE
  |
  +-- Slack assistant chat ------------> trigger=ASSISTANT_MESSAGE
  |
  +-- ConversationTriggerPolicy -------> Ignore or Handle
```

## Session identity

Thread-scoped Slack events map to the Slack thread session:

```text
SessionId(channelId, threadTs)
```

Root channel app mentions and root channel messages start a new Sidekick session keyed by the root message itself:

```text
SessionId(channelId, messageTs)
```

That distinction matters. A root app mention has no prior thread context; Sidekick's reply creates the Slack thread. A thread event can have existing human history, so Sidekick can bootstrap local state from Slack.

Direct messages use the Slack DM channel as the session:

```text
SessionId(dmChannelId, "")
```

```text
Root channel mention
  Slack:  channel=C123, ts=M1, thread_ts=null
  State:  SessionId(C123, M1)
  Reply:  posted with thread_ts=M1

Thread message or thread mention
  Slack:  channel=C123, ts=M2, thread_ts=T1
  State:  SessionId(C123, T1)
  Reply:  posted with thread_ts=T1

Direct message
  Slack:  channel=D123, ts=M3, thread_ts=null
  State:  SessionId(D123, "")
  Reply:  posted to D123
```

## History ownership

Sidekick's persisted session state is the source of continuity after a session exists locally. Slack thread history is only bootstrap material.

Future-us: `seedHistory` is the current mechanical flag on `TriggerDecision.Handle`. The deeper domain concept may be whether Sidekick is joining a session or continuing one; rename this when that language hardens enough to pay rent.

The seeding rule is:

```text
Seed from Slack only when the trigger decision allows seeding, local session history is empty, and the incoming conversation is thread-scoped.
```

In practice, the adapter passes a history loader on every interaction, but the loader returns Slack history only when Slack provides a `thread_ts`. `ConversationTriggerPolicy` decides whether the turn may seed history; `AgentSessions` decides whether local history is empty before calling the loader.

This keeps the model stable:

- Re-fetching Slack on every turn.
- Replaying old Slack messages into local state repeatedly.
- Treating Slack as the long-term source of truth after Sidekick has started managing the session.

```text
Incoming turn
  |
  +-- ConversationTriggerPolicy
        |
        +-- Ignore ----------------------------> stop
        |
        +-- Handle(seedHistory=false) ---------> use local history only
        |
        +-- Handle(seedHistory=true) ----------> AgentSessions checks local state
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

The triggering message is recorded through the normal turn path. That keeps the turn boundary clean: seeded messages become context, the triggering message becomes the current turn.

## Persisted history

Session state is stored under the configured agent state directory, not the workspace. The important files are:

- `messages.jsonl` for persisted session messages.
- `inflight.json` for current/last turn state.

Incoming user messages and assistant replies are both persisted. Assistant replies are normalized before storage: leading/trailing whitespace is trimmed, whitespace runs collapse to one space, and text is capped at 3200 characters.

`AgentSessions` stores live session messages directly. It does not compact session history.

## Gotchas

Root channel app mentions should not seed history. There is no Slack thread yet; the root message starts the Sidekick session.

Thread app mentions, direct-message thread messages, and assistant thread messages seed if local history is empty. Passive channel thread messages do not seed; they only continue an existing local Sidekick session.

Channel message events never invite Sidekick. They may only continue an already-known thread session. If local state is gone, a passive thread message is ignored until Sidekick is mentioned again.

Slack event identity is `channel + ts`, not message text. Text parsing is not a reliable deduplication strategy.

The Slack history loader excludes the triggering message using `currentTs`. If that exclusion breaks, prompt history can contain the same user message both as transcript and current message.

Seeded human messages may have weaker author data than live incoming messages depending on what the Slack history loader resolves. Prompt rendering must tolerate missing authors.

## Key files

- [ConversationTriggerPolicy.kt](/Users/anton/projects/sidekick/src/main/kotlin/com/github/uncomplexco/sidekick/application/sessions/ConversationTriggerPolicy.kt) – Trigger decisions for app mentions, passive messages, and assistant messages.
- [HandleIncomingChatMessageUsecase.kt](/Users/anton/projects/sidekick/src/main/kotlin/com/github/uncomplexco/sidekick/usecases/HandleIncomingChatMessageUsecase.kt) – Conversation use case that records incoming messages, runs Sidekick, posts replies, and records assistant output.
