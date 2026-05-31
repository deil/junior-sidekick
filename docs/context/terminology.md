# Project Terminology

Project-level glossary for `sidekick` terms that should be used consistently in code, docs, and product discussions.

## Conversation model

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Sidekick** | The AI-backed assistant that users interact with through a chat platform. | Bot, agent, assistant |
| **Chat** | The external messaging platform where users interact with Sidekick. Slack is the current chat implementation; future implementations may use other chat platforms while preserving the same Sidekick-facing concepts. | Slack, channel, conversation |
| **Session** | An ongoing interaction with Sidekick through a chat platform. A session contains messages and turns; in Slack, it may map to a DM, thread, or channel conversation depending on context. | Chat, thread, channel |
| **Turn** | One user message and the Sidekick work performed in response to it. A turn is scoped within a session and ends when Sidekick replies, declines to reply, or completes another requested action. | Thread, session, request |
