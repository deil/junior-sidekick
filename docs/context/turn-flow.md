# Turn Flow

Sidekick processing is easiest to reason about as durable `Session` state plus a series of `Turn`s.

A chat message can start a new session or continue an existing one. The queue drains messages for the same conversation into a batch. If admitted, the batch triggers one turn: record all inputs, decide participation once, run Koog when needed, deliver one reply, and record the outcome for every input.

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
- Has `TurnContext`: conversation id, turn id, `lastMessageId`, session files, prior session history, and compactions.
- Carries the session intelligence level for this turn: `normal` by default, or `ultrathink` after the conversation has opted in.
- Ends when Sidekick records one assistant reply for all batch messages or marks them skipped.

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
   |     | detect user-invocable skill request on each batch message
  |     | materialize SessionMessage.explicitSkillInvocation before persistence
  |     v
  |
   +-- SessionManager.recordIncomingMessages
  |     |
  |     | load or create ConversationState
  |     | seed Slack history if allowed and local state is empty
   |     | record all current batch messages
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
   |   SessionManager.markMessagesSkipped
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
             SessionManager.markMessagesSkipped(reason=AGENT_FAILURE)
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

Explicit skill invocation preprocessing runs on each live batch message before session recording and stores the result on `SessionMessage.explicitSkillInvocation`. Seeded history is not preprocessed for skill invocation. The five-file limit applies separately to each live message.

`SessionManager.recordIncomingMessages` is the persistence boundary after preprocessing.

The coordinator compacts prior context once. Recording locks the session, loads or creates `ConversationState`, optionally seeds Slack history, upserts every batch message and its files, and saves once. It returns a `RecordedTurn` containing the ordered messages and one `TurnContext`, with all current input IDs excluded from prior history.

Seeded messages are context. The batch is current turn input. `lastMessageId` identifies its latest message.

### Turn participation

`TurnParticipation` decides whether Sidekick should respond during an admitted turn. It returns `Respond`, `Skip`, or `Unsubscribe`.

The latest unsubscribe request or explicit mention determines subscription and response precedence. Unsubscribe does not require a mention. Other deterministic checks apply per message:

- Explicit mentions reply.
- Empty messages skip.
- Messages directed at another Slack user skip.
- Acknowledgments skip.
- Passive non-private turns without assistant history skip.

Private assistant messages do not require prior assistant history. They are allowed to proceed even when the session has no assistant messages yet.

Any deterministic `Respond` makes the batch respond; all deterministic skips make it skip. Otherwise, `LlmParticipationClassifier` receives every batch message and author with shared prior history, and makes one model call.

### Agent turn

`AgentExecutor.execute` receives a `RecordedTurn` and the chat adapter.

Each batch message becomes a separate Koog user message with its author, attachments, and skill invocation. All are appended before one model request. Shared context appears only in the first user message. A single-author batch includes requester metadata there; multi-author batches omit it and use a null MCP execution identity. One agent run processes the full batch.

### Steering during execution

After a complete tool batch, the strategy drains pending input through the active queue inbox, appends all tool results followed by new user messages, and requests the next model response. Draining an empty inbox never releases queue ownership. The inbox expires when the turn handler exits. Initial model requests and final-text responses do not drain input; messages arriving after the final drain remain for the next turn.

`SteeringInbox.stopRequested()` is checked before starting a tool batch. After a batch finishes, `drain()` returns `SteeringInput.Messages` or `SteeringInput.StopRequested`. Stop takes precedence over queued input: the queue clears pending messages and ignores further input until the active handler exits. Tools already in the batch finish, but their results do not trigger another model request after Stop. A final model answer completes normally even if Stop was requested.

A stopped agent returns `TurnExecutionResult.Stopped`. The coordinator marks consumed inputs skipped with `USER_STOPPED`, records token usage, removes processing reactions, and ends activity before delivering `STOP_ACK`. Stop leaves the conversation subscribed. Messages arriving after the handler finishes can start a new turn.

For each requested tool in the stopped batch, Koog records an interruption error result without another model request. No synthetic user or assistant text is added. The coordinator stores the delivered acknowledgement in session history so stopped inputs are not reintroduced as pending skipped messages on the next turn.

`TurnCoordinator` prepares each steering message's files and skill invocation, records it through `SessionManager.appendIncomingMessages` under the existing turn ID, and adds processing reactions. Steering bypasses participation classification, including deterministic checks; subscription-command semantics are not separately implemented for steering.

The requester identity and MCP connections remain those of the initial batch. New user prompts omit shared context and requester metadata. Tools keep the same `TurnContext` instance, with its live `lastMessageId` and `sessionFiles` refreshed before the next model request. Successful reply recording and agent-failure outcomes include every consumed input, and lifecycle reactions cover them all. Usage is still counted once per completed turn.

The OpenRouter client is wrapped with Koog `RetryingLLMClient` using `RetryConfig.PRODUCTION`, so transient provider failures such as 429s and temporary unavailability are retried before the turn sees an error. If execution still fails, `TurnCoordinator` posts one compact temporary-failure reply and marks all batch messages skipped with `AGENT_FAILURE`; it does not record an assistant reply in session history.

Session intelligence level is per conversation and defaults to `normal`. The `enableTokenmaxxin` system tool switches the current conversation between `normal` and `ultrathink`; the selected intelligence level applies when future turns initialize Koog.

### Response delivery and completion

When Sidekick responds, `ResponseDelivery` delivers the response and returns the chat message id/timestamp. Runtime-failure and unsubscribe notifications, status lines, and platform-specific message formatting are owned by its adapter implementation.

After participation returns `Respond`, lifecycle reactions add `eyes` to every batch message. Successful delivery and persistence replace them with completion reactions. Agent or delivery failure removes processing reactions without adding completion reactions. Ignored, skipped, and unsubscribe batches do not receive processing reactions.

`SessionManager.recordAssistantReply` marks all batch inputs replied, stores one assistant message, counts execution usage once, saves the session, and completes the turn.

When participation returns `Skip`, `SessionManager.markMessagesSkipped` records the reason on every input and completes the turn without calling Koog or delivering a response. Agent/model failures also mark every input skipped with `AGENT_FAILURE` and deliver one temporary-failure notification.

## Boundaries

A session is durable continuity. A turn is a single work unit.

The turn boundary starts after `TurnAdmission` returns `TurnAdmissionDecision.Admitted`. It ends after one of:

- `SessionManager.recordAssistantReply`
- `SessionManager.markMessagesSkipped`
- `SessionManager.markMessagesSkipped` with an agent/model failure fallback reply
- an unhandled error before completion

`TurnContext` is not durable session state. It is the turn-local working set derived from session state for one run.

## Key files

- `interfaces/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/slack/SlackAppFactory.kt` - Slack event intake and mapping.
- `interfaces/src/main/kotlin/com/github/uncomplexco/sidekick/usecases/ReceiveChatMessageUsecase.kt` - Queued and immediate incoming-message entry points.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnCoordinator.kt` - Turn orchestration and completion/failure handling.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnAdmission.kt` - Batch admission and session selection.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnParticipation.kt` - Per-turn participation decision.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/SessionManager.kt` - Session state operations used during turns.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/Models.kt` - `ConversationId`, `ConversationState`, `SessionMessage`, compactions, inflight state.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnContext.kt` - Turn-local context.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/ConversationStateStore.kt` - Session persistence interface.
