# Set up Slack

Configure the Slack Events API request URL to point at Sidekick's endpoint:

```text
/slack/events
```

## Subscribe to bot events

Add these bot event subscriptions:

```text
app_mention
agent_session_stopped
message.channels
message.groups
message.im
message.mpim
```

## Grant bot token scopes

Grant these bot token scopes for development:

```text
app_mentions:read
assistant:write
chat:write
channels:history
channels:read
groups:history
groups:read
im:history
im:read
im:write
mpim:history
mpim:read
users:read
```

Declare the app as an agent in Slack's app settings. Reinstall the app if its granted scopes change.

## Native Stop button

Slack shows Stop for processing agent sessions when the app subscribes to `agent_session_stopped`. Sidekick uses `agents.sessions.setStatus` and explicitly sets `active` when work finishes. Custom loading text is not supported.

Stop discards pending input and blocks new tool batches. Sidekick lets running tools finish before confirming it stopped. Final model answers are delivered normally.

Verify Stop in the target workspace. If migrating from `assistant_view` to `agent_view`, note that Slack does not allow reverting that UI change. See [Slack's migration guide](https://docs.slack.dev/ai/migrating-to-agent-messaging).

## Optional scopes

Optional Slack tools require more scopes:

| Scope | Tools that need it |
| --- | --- |
| `canvases:read` | `slackCanvasCreate` |
| `canvases:write` | `slackCanvasCreate` |
| `files:read` | `slackCanvasCreate`, `slackFileDownload` |
| `files:write` | `attachFile` |
| `reactions:write` | `slackReactionAdd` |
| `users:read:email` | `slackUserSearch` |
