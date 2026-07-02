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

- Triggered by one admitted `InboundMessage`.
- Belongs to exactly one session.
- Starts after turn admission returns `TurnTriggerDecision.ShouldHandle`.
- Has `TurnContext`: conversation id, turn id, current files, prior session history, and compactions.
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
  | - InboundMessage
  | - ChatConversationId
  | - ChatPlatformAdapter
  |
  v
Turn admission
  |
  | InboundMessageFilter decides:
  | - Ignore
  | - ShouldHandle(conversationId, seedHistory, explicitMention)
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
  +-- ReplyDecisionService
  |     |
  |     | deterministic reply/skip policy
  |     | LlmReplyDecisionClassifier for ambiguous passive turns
  |     v
  |   ReplyDecision
  |
  +-- if shouldReply=false
  |     |
  |     v
  |   SessionManager.markMessageSkipped
  |     |
  |     v
  |   turn complete
  |
  +-- if shouldReply=true
        |
        v
      SidekickAgent.runTurn
        |
        +-- success: Koog returns reply text
        |     |
        |     v
      ChatPlatformAdapter.reply.postReply
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
- Mapping Slack payloads to `InboundMessage`, `ChatConversationId`, and `ChatPlatformAdapter`.
- Reply delivery through Slack.

Application code should not need raw Slack payloads after this stage.

### Turn admission

`InboundMessageFilter` decides whether a normalized chat message becomes a turn.

It returns either:

- `TurnTriggerDecision.Ignore`
- `TurnTriggerDecision.ShouldHandle(conversationId, seedHistory, explicitMention)`

Admission also selects the session. A root app mention creates a session from the message id. A thread message maps to the thread session. A direct assistant message maps to the direct-message session.

### Session recording

Explicit skill invocation preprocessing runs before session recording. It detects deterministic user-invocable skill syntax only on the current inbound message and stores the result on `SessionMessage.explicitSkillInvocation`. Seeded history is not preprocessed for skill invocation.

`SessionManager.recordIncomingMessage` is the persistence boundary after preprocessing.

It locks the session, loads or creates `ConversationState`, optionally seeds Slack history, records the current user message, compacts context if needed, saves session state, and returns `TurnContext`.

Seeded messages are context. The triggering message is the current turn input.

### Reply policy

`ReplyDecisionService` decides whether the turn should produce a reply.

Deterministic decisions happen first:

- Explicit mentions reply.
- Empty messages skip.
- Messages directed at another Slack user skip.
- Acknowledgments skip.
- Passive non-private turns without assistant history skip.

Private assistant messages do not require prior assistant history. They are allowed to proceed even when the session has no assistant messages yet.

Ambiguous passive turns go to `LlmReplyDecisionClassifier`.

### Agent turn

`SidekickAgent.runTurn` receives `TurnContext` and `TurnMessage`.

It builds the prompt, selects the configured Koog model profile from `TurnContext.intelligenceLevel`, runs Koog, executes tools as needed, and returns reply text. This is the point where Sidekick generates the actual answer.

The OpenRouter client is wrapped with Koog `RetryingLLMClient` using `RetryConfig.PRODUCTION`, so transient provider failures such as 429s and temporary unavailability are retried before the turn sees an error. If agent execution still fails after retries, `TurnExecutor` posts a compact temporary-failure reply and marks the triggering message skipped with `AGENT_FAILURE`; it does not record an assistant reply in session history.

Session intelligence level is per conversation and defaults to `normal`. The `enableTokenmaxxin` system tool switches the current conversation between `normal` and `ultrathink`; the selected intelligence level applies when future turns initialize Koog.

### Reply delivery and completion

When Sidekick replies, the chat adapter posts the reply and returns the chat message id/timestamp.

`SessionManager.recordAssistantReply` then marks the user message as replied, stores the assistant message, updates inflight state, saves the session, and completes the turn.

When Sidekick skips by reply policy, `SessionManager.markMessageSkipped` records the skip reason and completes the turn without calling Koog or posting to chat. Agent/model failures are different: Sidekick posts a temporary-failure reply first, then marks the triggering message skipped with `AGENT_FAILURE`.

## Boundaries

A session is durable continuity. A turn is a single work unit.

The turn boundary starts after `InboundMessageFilter` returns `TurnTriggerDecision.ShouldHandle`. It ends after one of:

- `SessionManager.recordAssistantReply`
- `SessionManager.markMessageSkipped`
- `SessionManager.markMessageSkipped` after an agent/model failure fallback reply
- an unhandled error before completion

`TurnContext` is not durable session state. It is the turn-local working set derived from session state for one run.

## Key files

- `app/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/slack/SlackAppFactory.kt` - Slack event intake and mapping.
- `app/src/main/kotlin/com/github/uncomplexco/sidekick/usecases/HandleIncomingChatMessageUsecase.kt` - Current turn orchestrator.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/SessionManager.kt` - Session state operations used during turns.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/Models.kt` - `ConversationId`, `ConversationState`, `SessionMessage`, compactions, inflight state.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnContext.kt` - Turn-local context.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/ports/ConversationState.kt` - Session persistence port.
