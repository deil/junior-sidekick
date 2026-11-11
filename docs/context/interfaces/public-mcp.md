---
type: Spec
---

# Public MCP

Status: draft; no public MCP server implemented.

## Goal

Expose Sidekick as an MCP server so other agents can interact with it on behalf of an authenticated user, scoped to a specific project (chat channel).

This is an inbound interface to Sidekick, separate from the [outbound MCP tools](../tools/mcp.md) Sidekick calls.

## Domain model

```text
Interaction
├── Authenticated user
├── Project
│   ├── Project UID
│   └── Chat channel
├── Session
└── User/task input
```

- **Authenticated user:** the Sidekick user represented by the caller's authentication. An external agent acts on their behalf, not as a separate privileged Sidekick user.
- **Project:** the explicit scope of the interaction, mapped to a chat channel. Selecting a project does not grant access to it.
- **Session:** conversation continuity within that project. Creating or continuing a session is a separate choice from selecting the project.
- **External agent:** the MCP client submitting input and receiving Sidekick's result.

## Requirements

- Authentication determines the acting user; tool arguments cannot override that identity.
- Every interaction has one project scope. The user must be authorized for that project.
- Session access must respect both the authenticated user and project scope. Knowing a session ID is not authorization.
- Project context, workspace, tools, and delegated work use the authorized project and acting-user context.
- MCP access does not grant additional authority over chat or WebUI access.
- The MCP adapter invokes core agent execution; it does not implement a separate agent loop.
- MCP protocol sessions and Sidekick conversation sessions are distinct concepts.

Illustrative interaction, not a finalized tool contract:

```text
Authenticated MCP client
  → select authorized project
  → submit input to a new or existing Sidekick session
  → receive Sidekick's result
```

## Open decisions

- Transport, endpoint, authentication flow, and compatibility with existing WebUI authentication.
- Project discovery, selection, and source of project-access authorization.
- MCP tool names and arguments; session creation, continuation, and history access.
- Relationship between MCP sessions and chat threads; whether MCP work posts anything to chat.
- Long-running execution, progress, cancellation, and result retrieval.
- Available capabilities and handling of tools that require chat delivery or interactive authentication.

## Related context

- [Web authentication](web-authentication.md)
- [Project context](../project-context.md)
- [Turn flow](../turn-flow.md)
- [Slack access context](slack/access-context.md)
