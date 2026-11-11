---
type: Spec
---

# Slack access context

Status: draft design; no runtime enforcement.

## Goal

Support rules that restrict access to company data and tools based on Sidekick's home organization, message authors, and the audience receiving its output.

An external user can address Sidekick in a Slack Connect channel. An internal user can also request internal data in a channel visible to external users. Requester identity and reply audience are separate policy inputs.

## Domain model

```text
Access context
├── Home installation
│   ├── Workspace ID
│   └── Sidekick username (ID)
├── Conversation
│   ├── Channel ID
│   ├── Kind: channel | DM | group DM
│   ├── Visibility: public | private (channels only)
│   └── Shared: yes | no | unknown
└── Message authors (each contributing author)
    ├── Username (ID)
    ├── Workspace ID, when known
    ├── Membership: home | external | unknown
    ├── Kind: human | bot | unknown
    └── Guest: yes | no | unknown
```

- **Home installation:** the workspace where Sidekick is installed. Identity comes from the installation credentials, not the message author or event envelope's routing workspace.
- **Conversation:** where messages and replies are exchanged; it also represents the reply audience. There is no separate audience or reply-destination object.
- **Message author:** the source of a contributing message. `username` means ID, not display name. Preserve context for every author, including multi-author batches and steering input.
- **Membership:** derived by comparing the author's workspace ID with the home workspace ID. A match is `home`; a different ID is `external`; unresolved membership is `unknown`. Enterprise membership and workspace allowlists are out of scope.
- **Shared:** Slack's `is_shared`, without distinguishing Slack Connect from Enterprise sharing. This is independent of public/private visibility and author membership.
- **Author kind:** human or bot, independent of membership and sharing; available to policy rules.
- **Guest:** contextual information only. Guests in the home workspace count as home-workspace users; no guest-specific rules are planned.
- **Access context:** facts supplied to authorization policy, not an authorization decision. Unavailable facts remain unknown. Fact-resolution metadata includes observation time; its representation is undecided.

## Slack sources

- `auth.test`: bot user ID and installation workspace ID.
- `conversations.info`: channel visibility and `is_shared`. Any shared channel crosses the home-workspace boundary; no distinction between Slack Connect and Enterprise organization sharing is needed.
- `users.info`: author workspace, guest flags, and bot status. `is_stranger=false` does not establish home-workspace membership.
- Email domains, display names, channel names, and channel ID prefixes are not authorization evidence.
- Guests can belong to the home workspace. Workspace membership alone does not establish employee status.
- Guest status is contextual information; guest-specific rules are out of scope. Guests follow the same home-workspace classification as other local accounts.

## Scenarios

A shared channel connects workspaces; its host workspace does not determine who can read Sidekick's replies. Classify channel scenarios by `is_shared` and author workspace, not channel ownership.

| Conversation | Author | Scenario |
| --- | --- | --- |
| Non-shared channel | Home workspace | Local member, including a possible guest. |
| Non-shared channel | Other workspace | Not an ordinary live Slack Connect channel message; retain the facts rather than assuming the author is local. |
| Shared channel | Home workspace | Local requester, cross-workspace audience. |
| Shared channel | Other workspace | External requester, cross-workspace audience. |
| DM | Home workspace | Direct conversation with a local member, including a possible guest. |
| DM | Other workspace | Direct conversation with an external member. |
| Group DM | Home or other workspace | Author workspace alone does not establish the audience; other participants may be external. |

Each scenario can involve humans or bots. Guest status does not change workspace membership. Unknown membership or sharing remains a separate unresolved case.

## Proposed boundary

The Slack adapter resolves Slack facts and translates them into typed access context. Application policy consumes that context; it does not call Slack or inspect Slack SDK objects.

```text
Slack installation + conversation + authors
                  ↓
          typed access context
                  ↓
           policy decision
                  ↓
   permitted execution and delivery
```

Access context is separate from project classification and participation classification. Project `public`/`private`/`dm` does not describe external exposure. `shouldReply` decides participation, not authorization.

## Proposed invariants

- Missing facts remain unknown; a failed lookup must not imply internal membership or an internal-only audience.
- Every author in a batch has context. A multi-author turn must not lose security context because it has no single requester.
- Steering messages require the same authorization as initial input; joining an active turn cannot bypass policy.
- Delegated work inherits the parent's restrictions. Starting a subagent cannot grant additional access.
- Authorization covers data supplied to the model, tool execution, and output delivery. Hiding tool definitions alone does not protect internal prompts, history, project instructions, or workspace files.
- Channel sharing and membership can change. The persisted project type is not an authoritative security snapshot.
- Denials have explicit reasons without disclosing protected data.

Illustrative rule, not a selected default:

```text
allow company-data access only when:
  every contributing author is an allowed home-organization member
  AND the conversation is permitted to receive company data
```

## Open decisions

- Treatment of external bots and unresolved membership.
- Whether external conversations are blocked entirely or receive a restricted capability set.
- How fresh facts must be at admission, tool execution, and delivery; behavior when resolution fails.
- Representation of access context in turns, queued input, delegated executions, and persisted/resumed work.
- Coverage of external DMs, group DMs, scheduled work, and existing history when a conversation becomes externally shared.
- Rule configuration and denial behavior.

## Related context

- [Slack](slack.md)
- [Turn flow](../../turn-flow.md)
- [Subagents](../../tools/subagents.md)

## Sources

- [Slack Connect semantics](https://docs.slack.dev/apis/slack-connect/)
- [Authentication identity](https://docs.slack.dev/reference/methods/auth.test/)
- [User membership and guest fields](https://docs.slack.dev/reference/objects/user-object/)
