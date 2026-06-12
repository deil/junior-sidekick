# Slack

Implementation snapshot of Slack-specific semantics Sidekick relies on when translating Slack runtime data into chat, session, and turn concepts.

## Channel identifiers

Slack always identifies conversations with a channel id, including direct messages. Sidekick must preserve that id when mapping Slack events into `ChatConversationId`.

Known Slack channel id prefixes:

- `C` – public channel.
- `G` – private channel or multi-person direct message.
- `D` – direct message channel.

The `D` prefix is the reliable signal that a Slack conversation is a direct message. A missing channel id is not a valid Slack DM representation.

## Thread identifiers

Slack root messages have `ts` and no `thread_ts`. Thread replies have their own `ts` plus `thread_ts` pointing at the root message.

Sidekick maps Slack conversations as:

```text
root channel message: ChatConversationId(channelId = channel, threadId = null)
thread message:       ChatConversationId(channelId = channel, threadId = thread_ts)
direct message:       ChatConversationId(channelId = D..., threadId = thread_ts when present)
```

Do not discard the Slack channel id for DMs. It is needed for reply delivery, session identity, logging, and state storage.

## Message identity

Slack uses the `ts` field as both the message timestamp and the message id within a channel. Sidekick treats it as the chat message id when mapping Slack messages into session history.

The `ts` value is only unique together with the Slack channel id:

```text
message identity = channel + ts
```

This is the same identity used for deduplication. Do not invent a separate Slack message id; there usually is not one in the event payload.

## Events

Sidekick treats Slack `channel + ts` as the event identity for deduplication. Slack can emit more than one event for the same visible message, so handlers must be idempotent at that boundary.

```text
dedupe key = channel + ts
```

Event ownership:

| Slack event | Sidekick trigger | When Slack emits it |
| --- | --- | --- |
| `app_mention` | `EXPLICIT_MENTION` | Emitted when a channel/thread message mentions Sidekick, including messages with uploaded or forwarded files. |
| `message.*` | `PASSIVE_MESSAGE` | Emitted for ordinary channel/thread messages that do not mention Sidekick. |
| `message:file_share` | `PASSIVE_MESSAGE` | Emitted for file-share messages that do not mention Sidekick. |
| Slack assistant message | `ASSISTANT_MESSAGE` | Emitted for direct assistant-chat messages, including messages with files. |

Duplicate cases seen in practice:

| Visible Slack action | Possible events | Owner |
| --- | --- | --- |
| Channel message mentioning Sidekick | `app_mention` and `message.*` | `app_mention` |

## Channel Lookup

`SlackChannelTools.slackChannels` lists Slack public/private channels visible to Sidekick via `conversations.list`.

When `query` is blank or omitted, it returns the current page of channels; otherwise it filters the fetched page by normalized channel name, ignoring case and a leading `#`.

Search is page-based: if `hasMore` is true, callers should continue with `nextCursor` because later pages may contain more matches.

## File Attachments

| File source | Payload location |
| --- | --- |
| Directly attached files | `event.files` |
| Forwarded message files | `event.attachments[].files` |

When both direct files and attachment files exist, Sidekick uses direct files and ignores attachment files for that turn.
