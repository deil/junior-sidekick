# Project Terminology

Project-level glossary for `sidekick` terms that should be used consistently in code, docs, and product discussions.

## Conversation model

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Chat** | The external messaging platform where users interact with the agent. Slack is the current chat implementation; future implementations may use other chat platforms while preserving the same agent-facing concepts. | Slack, channel, conversation |
| **Turn** | One user message and the agent work performed in response to it. A turn is scoped within an ongoing conversation and ends when the agent replies, declines to reply, or completes another requested action. | Thread, session, request |
