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

## Agent sessions and Stop

`Todo` requires an outcome-focused `goal` describing the current user's ask and the
full updated task list. It returns that list as a JSON array.
Each item requires `content`, `status` (`pending`, `in_progress`, `completed`,
`cancelled`), and `priority` (`high`, `medium`, `low`). Sidekick posts one todo
message per turn in the reply thread (or starts a thread) and replaces its full
text on subsequent calls. The final response is separate, in the same thread.
Ending the turn leaves the todo message unchanged. Delivery failures are ignored;
after a failed initial post, the next invocation tries posting again. Delivery does
not record a message in session or Koog history; later Slack history reads work normally.
Internal progress updates and the processing indicator are separate. Delegates return the list without
publishing a message. The message preserves Slack mrkdwn formatting in the goal,
shows one status-icon task per line, and ends with italic `todos as of <updated_at>`
in the app's local timezone (`HH:mm:ss`). Priority remains in the tool
arguments and result, but is hidden in the message.
Indicators are plain characters: `◦` pending, `∗` in progress, `✓` completed, and `×` cancelled.
While a turn has pending or in-progress tasks, the italic footer includes elapsed
minutes since the last `Todo` call and refreshes every minute. The update time stays
fixed until the next call. Lists containing only completed/cancelled tasks omit the
age and stop refreshing; turn completion also stops refreshing.
`Todo` owns an in-memory snapshot of the goal, task list, and last tool-update time.
Both immediate updates and minute refreshes render that current snapshot.
Scheduled-job executions keep todo snapshots in memory without posting or refreshing
todo messages; their final response delivery is unchanged.

Sidekick uses `agents.sessions.setStatus`: `processing` while working, renewed every eight seconds, and `active` on completion or stop. The API has a one-hour processing timeout and no custom loading text. Posting a reply does not clear processing automatically. The legacy custom-status API did not enable Stop in the target workspace.

The `agent_session_stopped` handler acknowledges Slack immediately and requests a cooperative stop for the channel/thread, including work started by a root mention. The queue discards pending input and ignores input until that handler finishes. Other conversations are unaffected.

The agent checks for Stop before starting a tool batch and after draining steering input at the end of a batch. Running tools finish; their results are not sent to another model request after Stop. A final model answer is delivered normally. For a stopped turn, Sidekick records `USER_STOPPED`, clears processing reactions and activity, then sends `STOP_ACK`. The conversation remains subscribed.

Enable the bot event subscription and agent scopes described in [Set up Slack](../../../../SETUP_SLACK.md). App UI migration is a separate workspace configuration change.

## Channel Lookup

`SlackChannelTools.slackChannelsList` lists Slack public/private channels visible to Sidekick via `conversations.list`.

When a Slack channel/thread session reaches its first Koog turn, Sidekick looks up the current Slack channel with `conversations.info` and may render `name`, `topic.value`, and `purpose.value` as turn runtime context. `purpose.value` is treated as the channel description. Direct messages and multi-person direct messages are skipped; lookup failures silently omit metadata.

When `query` is blank or omitted, it returns the current page of channels; otherwise it filters the fetched page by normalized channel name, ignoring case and a leading `#`.

The tool scans Slack pages internally until it returns the requested number of matches or Slack has no more pages; if the text output includes `nextCursor`, callers can continue from there.

On Slack 429 rate limits, the tool does not sleep or retry; it returns any channels collected so far plus the current cursor and retry-after hint.

## History read tools

`slackChannelRead` reads channel history, defaulting to the current conversation's channel. `slackThreadRead` reads a thread identified by a Slack message archive `url` or both `channel_id` and `slack_ts`. They share channel resolution, access checks, pagination, retries, and conditional joining. There is no `slackChannelHistory` alias.

Both tools accept channel IDs, Slack channel mentions, `slack:<channel>` references, or exact stored channel names (case-insensitive, with optional leading `#`). Names resolve only against stored Projects in the current channel's known workspace, never by live channel-name scans. Unknown workspace or channel names require an explicit channel ID. Ambiguous names are rejected.

For threads, URL takes precedence when provided and cannot be blank. The resolver supports Slack `<url|label>` markup, HTTPS Slack/Slack Gov hosts, `/archives/<channel>/p<timestamp>`, and an optional `thread_ts` query parameter.

`slack_ts` identifies a root or child message. A URL's `thread_ts` takes precedence for the `conversations.replies` lookup; otherwise the supplied message timestamp is used.

The current conversation's channel is allowed, including private conversations. Other channels are rejected when persisted visibility is private and otherwise require live `conversations.info` metadata proving they are public. Channel IDs alone do not prove visibility. An inaccessible target is rejected.

On `not_in_channel`, either tool joins only a confirmed public channel whose metadata did not mark Sidekick as a member, then retries the complete read once from the original cursor and bounds. Successful joining is reported through `joined_channel`. Membership and missing-history-scope errors are returned as tool errors. Empty channel history succeeds with zero messages; empty thread history reports `No messages found for this thread.`

For both tools, `limit` defaults to 100 and is capped at 1000; `scan_depth` defaults to 10 and is capped at 10. Both must be positive. Each page requests at most 200 messages. Reads stop at the message limit, page budget, or end of Slack pagination. Channel messages retain Slack's returned order, normally newest first; thread replies are not expanded.

`slackChannelRead` also accepts nullable `cursor`, `oldest`, `latest`, and `bounds_inclusive`. Supplied string arguments cannot be blank. Time bounds accept Slack timestamps or `slack:<channel>:<timestamp>` references naming the resolved channel. Mismatched references are rejected. Thread reads do not accept cursor or range arguments.

Invalid cursors report:

```text
The supplied Slack history cursor is no longer valid. Retry the lookup without `cursor` to start from the newest page again.
```

Every history-read Slack request uses up to three attempts for transient transport/HTTP failures; HTTP 429 retries respect `Retry-After`. Slack application errors do not cause generic retries.

Both responses contain `channel_id`, `joined_channel`, `count`, `messages`, and nullable `next_cursor`. `channel_name` is nullable and omitted from JSON when unknown; channel IDs are not substituted for unknown names. No explicit truncation flag is returned.

Thread responses also include `thread_ts`. Root identification uses the URL's thread timestamp, then the first returned message's `thread_ts`, its `ts`, and finally the lookup timestamp. Only channel reads accept the returned cursor for continuation.

## File Attachments

| File source | Payload location |
| --- | --- |
| Directly attached files | `event.files` |
| Forwarded message files | `event.attachments[].files` |

When both direct files and attachment files exist, Sidekick uses direct files and ignores attachment files for that turn.
