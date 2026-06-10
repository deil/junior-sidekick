# Project Practices

Project-level architectural practices for `sidekick`. This file records how the codebase should be shaped as it grows.

## Core practices

### Philosophy

_Opinionated. Not everyone will agree — that's fine._

- Code is liability — less code means less complexity, fewer things that can break, and fewer things to maintain
- Prefer pragmatic simplicity — keep things small enough to fit in a context window (yours or the AI's)
- Optimize code for future maintainability — by humans or AI agents alike
- Make things and intent explicit — do not sugar-coat ugly truth behind clever abstractions or roundabout misdirection

### Code style

- Use JSpecify; apply `@NullMarked` by default. Or use Kotlin
- Use plural package names when a package holds a collection of similar types — `controllers`, `repositories`, `listeners`
- Name regex constants with the `_RE` suffix
- Organize method body into logical groups separated by a blank line:
  - **Guards / early returns** — validation, auth checks, precondition failures
  - **Setup / preparation** — variable declarations, data fetching, parsing
  - **Core logic** — main algorithm steps; each distinct step is its own group
  - **State mutations** — updating fields, signals, subjects
  - **Side effects** — emitting events, calling external services, logging outcomes

### Architecture

- Name use cases with the `Usecase` suffix (`CreateBookingUsecase`, not `CreateBookingUseCase`)
- Use cases must not call other use cases — extract shared logic into an application or domain service
- Validation belongs at the anti-corruption layer (API boundary, message consumer, etc.) — once data is inside the app, treat it as correct; do not re-validate inside methods; the exception: explicit business invariants that must hold before entering a workflow or algorithm
- Do not catch exceptions you don't know how to handle — unhandled errors propagate naturally to logs and 500 responses; wrapping or rethrowing without a clear corrective action is noise

### Tests

- Prefer Kotlin
- Structure tests as Arrange / Act / Assert
- Test behavior and intent, not structure — tests that assert on internal wiring break during refactoring without catching real regressions; heavy reliance on mocks is a sign you're testing structure
- Prefer real components in tests; fall back to test doubles when needed (typically port implementations); use mocks only as a last resort
- Maintain a centralized inventory of shared test doubles — don't implement them inline inside individual test classes

### Name the domain before naming the implementation

Use the terms in [terminology.md](terminology.md) when describing behavior and designing APIs. Prefer `Sidekick`, `Chat`, `Session`, and `Turn` over adapter-specific names unless the code is genuinely inside an adapter.

Implementation details belong near the implementation. Do not promote Slack payload fields, OAuth scopes, SDK class names, cache internals, or framework wiring into project language unless they expose a durable product concept.

### Keep chat adapters thin

Chat adapters translate platform events into Sidekick concepts and translate Sidekick replies back to the platform. They should handle platform-specific concerns such as event acknowledgement, payload conversion, deduplication, timestamp conversion, user lookup, and reply delivery.

Adapters should not own Sidekick behavior. Once an event has been translated into a session, message, author, and reply target, the rest of the work should move into use cases or application modules.

### Use cases orchestrate turns

Use cases should express the user-visible flow: receive a message, identify the session, record the incoming message, run the turn, deliver the reply, and record the assistant response.

Keep use cases readable as workflows. Push persistence mechanics, prompt construction, model execution, platform APIs, and routing classifiers behind focused modules.

### Sessions are the continuity boundary

Persisted session state is the source of continuity across turns. If behavior depends on conversation history, previous replies, skipped messages, or thread context, model it through session state instead of re-deriving it from scattered adapter details.

Session state should store normalized Sidekick concepts, not raw platform payloads.

### Turns are the unit of agent work

A turn starts from a user message and ends when Sidekick replies, declines to reply, or completes another requested action. Design agent execution, prompts, logging, and tests around this unit.

Avoid leaking transport events into turn logic. Slack may deliver multiple events for the same user message; Sidekick should still see one logical turn.

### Keep framework wiring boring

Spring, Slack Bolt, Koog, and storage wiring should assemble modules without becoming the design center. Configuration code should be easy to delete and recreate because the project behavior lives behind clearer application modules.

When wiring starts carrying business meaning, move that meaning into a named module and test it directly.
