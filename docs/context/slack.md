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

## Duplicate mention delivery

When a user mentions the bot in a channel, Slack can deliver both:

- an `app_mention` event
- a `message.*` event for the same Slack message

Sidekick treats `app_mention` as the owner of explicit mention handling. The matching `message.*` event must not produce a second turn.

Deduplication should use Slack message identity, not text parsing:

```text
dedupe key = channel + ts
```

Mention text detection may be useful as a routing guard, but it is not the source of truth for whether two Slack events represent the same message.
