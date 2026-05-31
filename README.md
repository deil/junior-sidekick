# Sidekick

Sidekick, Junior Sidekick.

[![Kotlin](https://img.shields.io/badge/kotlin-2.3-blue.svg?logo=kotlin)](http://kotlinlang.org)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

Sidekick is a Slack-based AI assistant for AI-pilled teams. It listens in Slack, runs an LLM-powered agent, and replies in DMs, channels, and threads.

## Overview

Sidekick is built for teams that want an assistant where the work already happens: Slack.

Based on this AI-generated paragraph, it receives Slack events, turns messages into agent turns, runs a Koog agent through OpenRouter, and posts the response back to the right Slack conversation. The project is intentionally small enough to understand and change, while leaving room for deeper capabilities such as workspace tools, skills, Slack artifacts, and MCP integrations. Eventually, Anton will re-write it to not be a hallucination. This day hasn't come yet, because Sidekick is not fully alive yet.

## Key Features

- Slack-native interaction through app mentions, DMs, channel messages, and Slack assistant chat.
- Thread-aware replies for channel conversations.
- Koog-based agent runtime using OpenRouter as the LLM gateway.

## Configuration

Configure the Slack Events API request URL to point at Sidekick's endpoint:

```text
/slack/events
```

Recommended bot event subscriptions:

```text
app_mention
message.channels
message.groups
message.im
message.mpim
```

Recommended bot token scopes for development:

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

## Technologies

- Kotlin / Spring Boot
- [Slack Bolt for JVM](https://github.com/slackapi/java-slack-sdk)
- [Koog](https://github.com/JetBrains/koog)

## Status

Sidekick is early-stage software. The core Slack-to-agent loop works, but APIs and runtime boundaries are still evolving. Expect sharp edges while the project settles.

## License

Sidekick is licensed under the [Apache License 2.0](LICENSE).
