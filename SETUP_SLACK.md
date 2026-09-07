# Set up Slack

Configure the Slack Events API request URL to point at Sidekick's endpoint:

```text
/slack/events
```

## Subscribe to bot events

Add these bot event subscriptions:

```text
app_mention
message.channels
message.groups
message.im
message.mpim
```

## Grant bot token scopes

Grant these bot token scopes for development:

```text
app_mentions:read
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

Optional Slack tools require more scopes:

| Scope | Tools that need it |
| --- | --- |
| `canvases:read` | `slackCanvasCreate` |
| `canvases:write` | `slackCanvasCreate` |
| `files:read` | `slackCanvasCreate`, `slackFileDownload` |
| `files:write` | `attachFile` |
| `reactions:write` | `slackReactionAdd` |
| `users:read:email` | `slackUserSearch` |
