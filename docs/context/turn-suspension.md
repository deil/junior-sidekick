# Turn Suspension

Turn suspension lets Sidekick pause a turn while waiting for an external action, release the running process, and later continue the same Koog execution. The motivating case is per-user MCP OAuth, where Sidekick must wait for a user to authorize an MCP server in their browser.

## Target flow

1. Sidekick works on a task in a session.
2. Koog decides it needs an MCP server for which the requesting user is not authorized.
3. Sidekick creates an OAuth authorization request with a Sidekick-owned callback URL.
4. Sidekick DMs the authorization URL to the requesting user.
5. The current turn becomes suspended and Sidekick stops executing it.
6. The browser eventually reaches Sidekick's callback URL.
7. If the suspended turn is still current, Sidekick resumes it from its Koog checkpoint.
8. Koog receives the completed authorization as the result of the original tool call and continues the turn.
9. Sidekick posts the final reply to the original session.

## Meaning of suspension

Suspension is durable checkpoint-and-exit, not a sleeping coroutine.

There is no live `AIAgent`, `AIAgentContext`, stack, coroutine, MCP connection, or chat adapter retained while a turn is suspended. Sidekick ends the current execution. On resume it creates a fresh agent execution, restores serialized Koog graph state, and continues after the last successfully checkpointed node.

This distinction matters because OAuth is controlled by a human and may never complete. Keeping a coroutine alive would be vulnerable to process restarts, retain turn-local resources indefinitely, and occupy the session's in-memory queue.

## Koog persistence

Koog's `Persistence` feature automatically checkpoints a graph after each successfully completed node. The feature receives `AIAgentContext` through Koog pipeline interceptors; Sidekick does not need to obtain the context of a running agent from outside.

A checkpoint contains the information needed to reconstruct graph execution, including:

- the last successfully completed node and its serialized output;
- LLM message history;
- selected model, parameters, and tools;
- serializable `AIAgentStorage` values;
- agent iteration state.

A checkpoint does not contain live Kotlin execution state or external resources.

When a strategy completes normally, Koog sends the persistence provider a terminal tombstone. Sidekick interprets that marker by clearing the active turn rather than storing the tombstone. A later turn therefore starts the graph from the beginning rather than resuming the completed execution. When execution exits before strategy completion, the latest checkpoint remains available for restoration.

### Checkpoint storage

Sidekick will use a custom latest-only Koog persistence provider. Saving a checkpoint replaces the previous checkpoint for that session; historical rollback is not required. This matches the domain invariant that a session has at most one current turn and that only its latest safe graph boundary can be resumed.

The checkpoint shares `runtime.json` with conversation usage statistics. The persisted parent shape is:

```text
ConversationRuntime
  stats: ConversationStats
  activeTurn: ActiveTurn?

ActiveTurn
  id: turn id
  checkpoint: Koog AgentCheckpointData
```

`activeTurn = null` means there is no resumable turn. Nesting the turn id and checkpoint prevents invalid persisted combinations such as a checkpoint without its turn identity or a resumable turn identity without checkpoint data. The turn id is first persisted when Koog produces a checkpoint.

The filesystem conversation store owns `runtime.json`. Ordinary conversation-state saves update statistics while preserving the active turn; Koog checkpoint saves update the active turn while preserving statistics. Both paths use the same session lock and atomic file replacement so independent read-modify-write cycles cannot lose either side of the document. `ConversationState` does not duplicate the active turn or its id; `runtime.json` is authoritative.

For migration, reads use `runtime.json` when it exists and otherwise fall back to legacy `stats.json`. Existing session directories are a real persisted-data caller, so this compatibility path is intentional. Writes always target `runtime.json` and remove `stats.json` after successful replacement. There is no separate `turn-checkpoint.jsonl`.

The provider's Koog interface behaves as follows:

- `saveCheckpoint` atomically replaces the current checkpoint;
- `getLatestCheckpoint` returns the one current checkpoint when its continuation is eligible;
- `getCheckpoints` returns either that checkpoint or an empty list;
- a successful strategy's tombstone clears the active turn;
- superseding a suspended turn clears the active turn before the new turn runs.

Automatic checkpoint creation remains enabled. Latest-only storage limits persistence volume without requiring Sidekick to obtain `AIAgentContext` or manually reproduce Koog's node-completion checkpoint logic. Automatic checkpoint creation does not grant authority to resume: durable turn state still decides whether the stored checkpoint is eligible for restoration.

## Checkpoint boundary for external actions

The Sidekick strategy needs an explicit node after tool execution that resolves or waits for external actions:

```text
LLM request
  -> execute tools
  -> await external actions
  -> send tool results
  -> LLM request
```

An OAuth tool must not throw a turn-suspension signal from inside tool execution. It must:

1. Create and persist the authorization request.
2. DM the authorization URL.
3. Return an internal `authorization_pending` result normally.

The complete tool-execution node then succeeds and Koog checkpoints all tool results. The following `await external actions` node inspects those results:

- If every referenced external action is complete, it replaces pending results with terminal results and continues.
- If any referenced external action is still pending, it exits execution with the turn-suspension signal.

Because the waiting node does not complete, the latest checkpoint remains immediately before it. Restoration continues after the tool-execution node and enters the waiting node again. It does not rerun the checkpointed tool batch.

A fast OAuth callback may arrive before the waiting node runs. This is safe: completion is persisted independently, so the node observes a completed authorization and never suspends.

## Parallel tool execution

Sidekick currently uses Koog's sequential default for `nodeExecuteTools`, although Koog supports parallel tool execution. Suspension must remain safe if parallel execution is enabled later.

The complete tool batch is the checkpoint barrier. Every tool in the batch, including tools unrelated to OAuth, finishes before the tool-execution node is checkpointed. Suspension happens only in the following waiting node. This prevents a normal suspend/resume cycle from rerunning tools that completed in the same batch.

One LLM response may create more than one external action. The waiting state must therefore represent a set of external action identifiers rather than one OAuth request. The turn can continue only when every action in that set is terminal.

Koog checkpoints nodes, not individual tool invocations. A process crash during a sequential or parallel tool-execution node may still cause already completed tools to run again when execution is restored from the preceding checkpoint. This is Koog's existing at-least-once tool-execution behavior, not a property introduced by turn suspension. Exactly-once side effects would require separate tool-level idempotency or durable execution records.

## Session behavior

A session with a running or suspended turn is busy. It has at most one current turn.

While a turn is actively running, later admitted messages wait normally. While a turn is suspended, a newly admitted message supersedes it:

```text
RUNNING
  -> external action required
SUSPENDED

SUSPENDED
  -> external action callback
RESUMING -> RUNNING -> COMPLETED

SUSPENDED
  -> new admitted message
CANCELLED -> new turn starts
```

Superseding a suspended turn must atomically:

- mark its triggering message skipped with a reason such as `SUPERSEDED`;
- make its continuation ineligible for resume;
- clear its active turn and Koog checkpoint;
- admit the new message as the current turn.

Cancellation does not roll back tools or other side effects completed before suspension. Those effects remain valid even though Koog does not continue the cancelled execution.

## Callback and message race

An OAuth callback and a new session message may arrive concurrently. They must arbitrate through durable session state under the session lock so exactly one transition wins.

- If the callback wins, the turn moves from `SUSPENDED` to `RESUMING`. The new message waits behind the resumed active turn.
- If the message wins, the turn moves from `SUSPENDED` to `CANCELLED`. The callback may complete authorization but must not resume the cancelled turn.

The callback must not rely on the original Slack event context or `ChatPlatformAdapter`; those are turn-local objects and no longer exist. Durable suspension state must contain normalized identities and reply addressing sufficient to reconstruct delivery through a fresh chat adapter.

## Authorization lifetime

The OAuth authorization request and the turn continuation have related but independent lifetimes.

Cancelling a suspended turn disables only its continuation. If the user later completes that OAuth flow, Sidekick should still store the user's authorization and show a successful browser result. Future turns may use the authorization, but the cancelled turn must not resume.

There is no turn timeout. A timeout would wake stale conversations and produce replies the requester no longer needs. A suspended turn ends through one of two meaningful events: authorization completion or a new admitted message that supersedes it.

## Chat memory

Koog `ChatMemory` remains necessary alongside `Persistence`:

- `ChatMemory` provides conversation continuity between completed turns.
- `Persistence` provides execution continuity within an incomplete turn.

On normal completion, `ChatMemory` stores final history and `Persistence` emits a terminal tombstone that clears the active turn. On suspension, `ChatMemory` does not store partial history because the strategy did not complete, while `Persistence` retains the graph checkpoint. During restoration, checkpoint state is applied inside graph execution after strategy-starting interceptors, so checkpoint history supersedes the older completed history initially loaded by `ChatMemory`. Once the resumed turn completes, `ChatMemory` stores the complete resulting history.

## Required durable concepts

Implementation details remain open, but the durable model must express at least:

- the current turn and whether it is running, suspended, resuming, completed, or cancelled;
- the suspended turn's Koog checkpoint identity;
- the original session and reply destination;
- the requesting user's stable identity;
- the set of external action identifiers awaited by the turn;
- whether a continuation is still eligible for resume;
- the independent status and result of each OAuth authorization request.

These concepts belong in application/session modules. Spring controllers, Slack event contexts, and Koog feature wiring should only adapt external events into these transitions.

## Rejected approaches

### Keep the coroutine suspended

Rejected because it is not durable across process failure or deployment, retains resources for an unbounded human delay, and blocks an in-memory queue rather than representing session state.

### Throw directly from the OAuth tool

Rejected because the tool-execution node would not complete. Restoration would resume from the preceding checkpoint and rerun the whole tool batch, potentially duplicating side effects.

### Use chat history as the continuation

Rejected because chat history preserves conversation content, not graph position or the serialized output needed to continue after a particular node. Starting a new prompt that says authorization completed is a new execution, not a resumed turn.

### Replace `ChatMemory` with persistence

Rejected because successful executions end with persistence tombstones. Persistence recovers incomplete graph execution; it does not provide history for future independent turns.

### Resume on a timer

Rejected because stale suspended work should not spontaneously produce a reply. New user intent supersedes stale work, while OAuth completion is the only event that should resume it.

## Relevant implementation

- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/TurnExecutor.kt` - current turn orchestration and completion/failure handling.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/koog/SidekickAgent.kt` - current Koog graph and agent construction.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/turn/koog/ConversationStateChatHistoryProvider.kt` - durable Koog chat history for completed turns.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/conversation/Models.kt` - current session and turn statistics state.
- `core/src/main/kotlin/com/github/uncomplexco/sidekick/application/chat/InboundQueue.kt` - current in-memory session message scheduling.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/mcp/McpOAuth.kt` - current global MCP OAuth state.
- `app/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/mcp/McpOAuthController.kt` - current OAuth callback adapter.
- [Koog agent persistence](https://docs.koog.ai/features/agent-persistence/) - checkpoint and restoration behavior.
