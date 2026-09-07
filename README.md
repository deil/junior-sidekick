# Sidekick

Sidekick, Junior Sidekick.

[![Kotlin](https://img.shields.io/badge/kotlin-2.3-blue.svg?logo=kotlin)](http://kotlinlang.org)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

Sidekick is a multiplayer AI assistant for AI-pilled teams. It listens in Slack or Discord, runs an LLM-powered agent, and replies in DMs, channels, and threads.

## Overview

Sidekick is built for teams that want an assistant where the work already happens: Slack (or, for some, Discord).

According to GPT-5.5 summary, it receives Slack events, resolves user identity, persists conversation history, runs a Koog agent through OpenRouter, and posts the response back to the right Slack conversation. Eventually, Anton will re-write it to not be a hallucination. This day hasn't come yet, because Sidekick is not fully alive yet.

## Key Features

- Slack-native interaction through app mentions, DMs, channel messages, and Slack assistant chat.
- Thread-aware replies for channel conversations.
- Koog-based agent runtime using OpenRouter as the LLM gateway.
- File-based workspace operations for reading, searching, and editing project files.
- Git tools for cloning, pulling, and pushing private GitHub and Bitbucket repositories.
- Sandboxed bash execution for running workspace commands with configurable network access.

## Configuration

- [Set up Slack](SETUP_SLACK.md)
- [Set up Discord](SETUP_DISCORD.md)

## Integrations

| Integration | Purpose |
| --- | --- |
| [Dumphere](https://github.com/uncomplex-co/dumphere) | Publishes internal HTML and Markdown files and returns share URLs. |

## Ingredients

- Kotlin / Spring Boot
- [Slack Bolt for JVM](https://github.com/slackapi/java-slack-sdk)
- [JDA](https://github.com/discord-jda/JDA)
- [Koog](https://github.com/JetBrains/koog)
- [Bubblewrap](https://github.com/containers/bubblewrap)
- [mise](https://mise.jdx.dev/)

## Status

Sidekick is a research preview, not a polished product. The core Slack-to-agent loop works, but the project is actively exploring what a Slack-native AI teammate can do for a team. Expect sharp edges and frequent changes while the shape of the product is still being discovered.

## License

Sidekick is licensed under the [Apache License 2.0](LICENSE).
