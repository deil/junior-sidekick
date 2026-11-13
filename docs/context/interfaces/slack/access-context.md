---
type: Spec
---

# Slack access context

Provide workspace, conversation, and author facts so rules can control access to company data and tools.

**Persistence and thread-start refresh implemented; authorization remains draft.** Stored facts are not yet bound into execution access context. Policy enforcement is not implemented.

## Domain model

```text
Access context
├── Home installation
│   ├── Workspace ID
│   └── Sidekick username (ID)
├── Conversation
│   ├── Channel ID
│   ├── Workspace ID, when known
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

**1 → Home means installation workspace.** Compare each author's workspace ID with it: matching is `home`, different is `external`, unavailable is `unknown`. Enterprise membership and workspace allowlists are out of scope.

**2 → Conversation includes the audience.** A home-workspace requester can still expose data by asking in a shared channel. No separate audience object is needed.

**3 → Shared means Slack's `is_shared`.** Treat Slack Connect and Enterprise-shared channels alike. Sharing is independent of public/private visibility; channel ownership does not determine access. The conversation workspace comes from `context_team_id`, not `conversation_host_id`; it does not describe every participating workspace.

**4 → Author kind is a policy input.** Home and external authors can each be humans or bots. Preserve facts for every author in a batch and for messages received during execution (steering).

**5 → Guest status is context only.** Home-workspace guests count as home users. No guest-specific rules are planned; workspace membership does not establish employee status.

**6 → Unknown stays unknown.** Missing facts must not imply a home user, human author, or non-shared conversation. `username` means ID, not display name.

## Data sources and freshness

**1 → Installation: `auth.test`.** Obtain Sidekick's username and home workspace ID from its credentials, not the message author or event envelope's routing workspace.

**2 → Conversation: `conversations.info` when Sidekick first enters a thread.** A thread is new to Sidekick when it has no persisted session. Fetch channel name, `context_team_id`, kind, visibility, and `is_shared`, including for existing projects with complete metadata. Continuing that thread does not refresh, even if metadata is incomplete. **Changes mid-thread are not detected until a new thread starts.** Snapshot storage and refresh boundaries for DMs and scheduled runs remain undecided.

**3 → Author: `users.info`.** Obtain workspace ID, bot status, and guest flags. Author refresh timing is undecided. `is_stranger=false` does not prove home-workspace membership; email domains, display names, channel names, and ID prefixes are not authorization evidence.

## Persistence

**1 → Channels.** `Project` stores nullable `chatWorkspaceId`, `channelKind`, `isShared`, and `channelDetailsUpdatedAt` alongside its existing visibility classification. `isMissingChatMetadata` is computed from missing workspace ID, channel kind, or sharing status. New channels are registered on admitted input; existing projects refresh at thread start without replacing project identity or creation time. Refreshes update the channel name when available; otherwise the known name is retained. Failed refreshes retain the previous values and successful-lookup timestamp; a failed first lookup leaves the new fields null. A later new thread retries.

**2 → Slack users.** `User` stores nullable `chatWorkspaceId`, `isBot`, `isGuest`, and `chatDetailsUpdatedAt`. Email is nullable: bots and users without accessible email can be stored. Email matches link the Slack identity and metadata to the existing user without replacing its name, IdP identity, or creation time.

**3 → Existing users backfill on interaction.** `User.isMissingChatMetadata` is computed from missing workspace ID, bot status, or guest status. Email does not count. Incomplete users trigger a lookup on admitted messages, including steering. Updates preserve identity, name, email, creation time, and any known metadata absent from the response. Failed lookups still record activity; incomplete results retry on a later interaction. Complete users need no lookup.

**4 → Existing rows remain valid.** New columns are nullable, with no guessed defaults or startup backfill. Nullable booleans preserve the distinction between false and unknown. V6 preserves existing user IDs and identities while allowing null email; non-null emails remain unique.

## Scenarios

| Channel | Author workspace | Meaning |
| --- | --- | --- |
| Non-shared | Home | Workspace-local conversation; guests are possible. |
| Non-shared | Other | Not a normal live Slack Connect message; do not reinterpret the author as local. |
| Shared | Home | Local requester, cross-workspace audience. |
| Shared | Other | External requester, cross-workspace audience. |

**1 → DMs can be local or external.** Classify the author by workspace ID; non-shared does not establish a local participant.

**2 → Group DMs need participant context.** The current author's workspace alone does not establish whether other participants are external. How to resolve those participants remains undecided.

**3 → Human/bot kind applies to every scenario.** Unresolved membership or sharing is an unknown case, not a local/non-shared case.

## Proposed authorization boundary

**1 → Adapter resolves facts; policy decides access.** The Slack adapter produces typed access context. Application policy does not inspect Slack SDK objects or call Slack directly.

**2 → Participation is separate from authorization.** `shouldReply` is not an access check. Project `public`/`private`/`dm` classification is not a security snapshot.

**3 → Checks cover input, tools, and output.** Hiding tool definitions does not protect internal prompts, history, project instructions, or workspace files already supplied to the model.

**4 → Steering and delegation cannot bypass policy.** Apply authorization to every contributing author, including steering messages. Subagents inherit the parent's restrictions.

**5 → Denials must not expose protected data.** Exact rules and denial behavior are undecided.

## Open decisions

**1 → Rules.** Block external conversations entirely or allow restricted capabilities; treatment of bots and unresolved facts; configuration and denial behavior.

**2 → Lifetime and storage.** Author-fact freshness, lookup-failure behavior, and context representation across turns, queues, subagents, and resumed execution. Conversation refresh is fixed at new-thread boundaries.

**3 → Other execution paths.** DM/group-DM resolution and refresh, scheduled work, and handling existing history when a conversation becomes shared.

## Related context

- [Slack](slack.md)
- [Turn flow](../../turn-flow.md)
- [Subagents](../../tools/subagents.md)

## Sources

- [Slack Connect semantics](https://docs.slack.dev/apis/slack-connect/)
- [Authentication identity](https://docs.slack.dev/reference/methods/auth.test/)
- [User membership and guest fields](https://docs.slack.dev/reference/objects/user-object/)
