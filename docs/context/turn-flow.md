# Turn Flow

Sidekick processing is easiest to reason about as durable `Session` state plus a series of `Turn`s.

A chat message can start a new session or continue an existing one. If admitted, it triggers one turn. The turn records the incoming message, builds context from session state, decides whether Sidekick should reply, runs Koog when needed, delivers the reply, and records the outcome.

## Core model

`Session` is continuity:

- Persisted and loaded across turns.
- Identified by `ConversationId(channelId, threadId)`.
- Owns `ConversationState`: messages, compactions, intelligence level, and inflight metadata.
- Managed by `SessionManager`.
- Stored through the `ConversationStateStore` port.

`Turn` is work:

- Triggered by one or more admitted `IncomingMessage` values in an `IncomingBatch`.
- Belongs to exactly one session.
- Starts after `TurnAdmission` returns `TurnAdmissionDecision.Admitted`.
- Has `TurnContext`: conversation id, turn id, current message id, session files, prior session history, and compactions.
- Carries the session intelligence level for this turn: `normal` by default, or `ultrathink` after the conversation has opted in.
- Ends when Sidekick records an assistant reply or marks the incoming message skipped.

## Processing stages

```text
Slack event
  |
  v
Slack adapter
  |
  | converts Slack payloads into:
  | - ChatConversationId
  | - IncomingMessage
  | - ChatPlatformAdapter
  |
  v
Turn admission
  |
  | TurnAdmission decides:
  | - Ignore
  | - Admitted(conversationId, seedHistory, explicitMention)
  |
  v
Turn processing
  |
  +-- explicit skill invocation preprocessing
  |     |
  |     | detect user-invocable skill request from current message text
  |     | materialize SessionMessage.explicitSkillInvocation before persistence
  |     v
  |
  +-- SessionManager.recordIncomingMessage
  |     |
  |     | load or create ConversationState
  |     | seed Slack history if allowed and local state is empty
  |     | record current user message
  |     | compact context if needed
  |     | save ConversationState
  |     v
  |   TurnContext
  |
   +-- TurnParticipation
  |     |
  |     | deterministic participation decision
  |     | LlmParticipationClassifier for ambiguous passive turns
  |     v
  |   TurnParticipationDecision
  |
  +-- Skip or Unsubscribe
  |     |
  |     v
  |   SessionManager.markMessageSkipped
  |     |
  |     v
  |   turn complete
  |
  +-- Respond
        |
        v
       AgentExecutor.execute
        |
        +-- success: Koog returns reply text
        |     |
        |     v
       ResponseDelivery.deliver
        |     |
        |     v
      SessionManager.recordAssistantReply
        |     |
        |     v
        |   turn complete
        |
        +-- agent/model failure:
              |
              v
            post temporary failure reply
              |
              v
            SessionManager.markMessageSkipped(reason=AGENT_FAILURE)
              |
              v
      turn complete
```

This is not just a list of filters. It is staged conversion with stop points. Filters decide whether processing continues. Continuing stages translate data into the next stage's language.

## Stage responsibilities

### Slack adapter

The adapter owns Slack-specific work:

- Event acknowledgement.
- Deduplication by `channel + ts`.
- Ignoring Sidekick's own messages.
- Ignoring Slack `message.*` events whose mention is owned by `app_mention`.
- Mapping Slack payloads to `IncomingMessage`, `ChatConversationId`, and `ChatPlatformAdapter`.
- Turn activity and response delivery through `TurnLifecycle`, `ResponseDelivery`, and `SlackTurnOutput`.

Application code should not need raw Slack payloads after this stage.

### Turn admission

`TurnAdmission` decides whether a normalized batch of messages from one chat conversation becomes a turn.

It returns either:

- `TurnAdmissionDecision.Ignore`
- `TurnAdmissionDecision.Admitted(conversationId, seedHistory, explicitMention)`

Admission also selects the session. A root app mention creates a session from the message id. A thread message maps to the thread session. A direct assistant message maps to the direct-message session.

### Session recording

Explicit skill invocation preprocessing runs before session recording. It detects deterministic user-invocable skill syntax only on the current inbound message and stores the result on `SessionMessage.explicitSkillInvocation`. Seeded history is not preprocessed for skill invocation.

`SessionManager.recordIncomingMessage` is the persistence boundary after preprocessing.

It locks the session, loads or creates `ConversationState`, optionally seeds Slack history, records the current user message, compacts context if needed, saves session state, and returns a `RecordedTurn` containing `TurnContext` and the current session message.

Seeded messages are context. The triggering message is the current turn input.

### Turn participation

`TurnParticipation` decides whether Sidekick should respond during an admitted turn. It returns `Respond`, `Skip`, or `Unsubscribe`.

Deterministic decisions happen first:

- Explicit mentions reply.
- Empty messages skip.
- Messages directed at another Slack user skip.
- Acknowledgments skip.
- Passive non-private turns without assistant history skip.

Private assistant messages do not require prior assistant history. They are allowed to proceed even when the session has no assistant messages yet.

Ambiguous passive turns go to `LlmParticipationClassifier`.

### Agent turn

`AgentExecutor.execute` receives a `RecordedTurn` and the chat adapter.

It builds the prompt, selects the configured Koog model profile from `TurnContext.aiModelProfile`, runs Koog, executes tools as needed, and returns reply text. This is the point where Sidekick generates the actual answer.

The OpenRouter client is wrapped with Koog `RetryingLLMClient` using `RetryConfig.PRODUCTION`, so transient provider failures such as 429s and temporary unavailability are retried before the turn sees an error. If agent execution still fails after retries, `TurnCoordinator` posts a compact temporary-failure reply and marks the triggering message skipped with `AGENT_FAILURE`; it does not record an assistant reply in session history.

Session intelligence level is per conversation and defaults to `normal`. The `enableTokenmaxxin` system tool switches the current conversation between `normal` and `ultrathink`; the selected intelligence level applies when future turns initialize Koog.

### Response delivery and completion

When Sidekick responds, `ResponseDelivery` delivers the response and returns the chat message id/timestamp. Runtime-failure and unsubscribe notifications, status lines, and platform-specific message formatting are owned by its adapter implementation.

After turn participation returns `Respond`, the Slack adapter adds `:eyes:` to the triggering message. Successful final delivery and persistence replace it with `:white_check_mark:`. Agent or delivery failure removes `:eyes:` without adding a completion reaction. Ignored, skipped, and unsubscribe messages do not receive processing reactions.

`SessionManager.recordAssistantReply` then marks the user message as replied, stores the assistant message, updates inflight state, saves the session, and completes the turn.

When turn participation returns `Skip`, `SessionManager.markMessageSkipped` records the reason and completes the turn without calling Koog or delivering a response. Agent/model failures are different: Sidekick delivers a temporary-failure response first, then marks the triggering message skipped with `AGENT_FAILURE`.

## Boundaries

A session is durable continuity. A turn is a single work unit.

The turn boundary starts after `TurnAdmission` returns `TurnAdmissionDecision.Admitted`. It ends after one of:

- `SessionManager.recordAssistantReply`
- `SessionManager.markMessageSkipped`
- `SessionManager.markMessageSkipped` after an agent/model failure fallback reply
- an unhandled error before completion

`TurnContext` is not durable session state. It is the turn-local working set derived from session state for one run.

## Key files

- `app/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/slack/SlackAppFactory.kt` - Slack event intake and mapping.
- `app/src/main/kotlin/com/github/uncomplexco/sidekick/usecases/ReceiveChatMessageUsecase.kt` - Queued and immediate incoming-message entry points.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnCoordinator.kt` - Turn orchestration and completion/failure handling.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnAdmission.kt` - Batch admission and session selection.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnParticipation.kt` - Per-turn participation decision.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/SessionManager.kt` - Session state operations used during turns.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/Models.kt` - `ConversationId`, `ConversationState`, `SessionMessage`, compactions, inflight state.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnContext.kt` - Turn-local context.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/ConversationStateStore.kt` - Session persistence interface.
