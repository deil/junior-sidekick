# Core refactor

The tool-approval experiment exposed weak areas in the current architecture. These are the directions to work through in stages, before reintroducing approvals.

## Checkpointing and recovery

Current checkpoints leave too much live state in memory. If Sidekick is terminated mid-turn, it must be able to continue execution after restart without requiring the user to resend their input.

The inbound message queue must also be persisted. Recovering the active turn while losing messages queued behind it is insufficient.

Recovery needs to cover the execution state, consumed and pending input, requester identity, retained outputs, and progress of delegated work. Live clients and connections should be recreated from persisted configuration.

## Shared agent execution

Sidekick, Task, and Loop currently construct and run agents separately. They need a common core agent loop that:

- Receives a system prompt, tools, MCP servers, and user/task input.
- Executes the turn and returns its result.
- Supports checkpoints and resume/continuation.
- Is decoupled from delivery, tool construction, and prompt generation.

This primitive should back Task and Loop and be used by the high-level Sidekick agent. Loop still owns its iteration and validation logic.

## Orchestrator and delegated agents

Sidekick is the user-facing orchestrator. Its system prompt includes world context, communication style, fact-checking, and instructions for receiving user requests and delegating work.

Task and Loop do not interact directly with the user. Their caller supplies their task definitions and system prompts. The core execution mechanism should not know how those prompts were generated.

Agent orchestration is separate from application lifecycle orchestration, which already exists in `TurnCoordinator`.

## Global tool registry

Use one global tool registry, with some tools available to all agent types and others available only to the orchestrator. Each execution receives the allowed set rather than assembling an independent registry.

Global registration does not mean sharing mutable tool instances or live connections. Tools still need to be bound to the execution's context and identity.

The runtime interface, tool classifications, recovery semantics for interrupted tool calls, and implementation order remain open.

## Interface adapters

Support CLI, HTTP API, and exposing the agent through MCP, in addition to chat interfaces.

The agent stays in `core/`. Interface adapters live in a separate module and invoke the core agent without coupling it to their transport or delivery mechanism.

## Waiting for external events

Sidekick should be able to wait for external events, either by polling or by suspending execution until a webhook arrives, then continue with the event's findings.

Example: "Build this feature and do not bother me until completion."

1. Implement the change and create a PR.
2. Wait for CI/CD builds, tests, and reviews to produce results.
3. Resume with those findings, address them, and update the PR.
4. Wait for the next results and repeat until the completion conditions are met.
5. Report completion to the user rather than requesting their intervention between iterations.

### Assistant notes

- Waiting state and pending polling work need to survive restart, just like active execution and queued input.
- Polling and webhooks should be alternative ways of satisfying a persisted wait, with results associated with the correct execution and PR revision.
- A suspended execution should release live resources. It should not require a coroutine or process to stay asleep until the event arrives.

## Further decisions: checkpoints and tool recovery

### Independent agent checkpoints

Every low-level agent primitive supports checkpointing out of the box through an injected persistence interface. The primitive saves its own execution state without depending on a particular storage mechanism.

Each agent is persisted independently under an identifier. A parent's checkpoint contains references to child snapshots, never embedded child state. Persistence does not need to understand or enforce the dependency graph. The orchestrator supplies persistence to its children.

The exact recovery/resume contract, including how child references resolve to a resume point, remains open.

### Interrupted tool calls

Keep initial recovery simple. Preserve completed tool results. For any incomplete tool call, supply an error result on resume instead of automatically re-running it. Tell the model that execution was interrupted and the operation may have taken effect; let it verify the outcome and decide how to recover.

Persist the tool-call request and an execution-started marker. More specific recovery behavior based on that marker is future work. Do not attempt exactly-once execution or add machinery to guarantee whether an interrupted external operation succeeded.

### Future tool metadata and approvals

Built-in, extension, and MCP tools should eventually carry behavior metadata such as `readonly`, `mutating`, `destructive`, and `idempotent`. This metadata will inform tool approvals and may also inform recovery.

The tagging scheme remains open. These properties need not be mutually exclusive; a mutating or destructive operation can also be idempotent. Metadata-driven approval and recovery are future work, separate from the initial interrupted-call error behavior.
