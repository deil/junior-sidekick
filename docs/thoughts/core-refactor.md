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
