# Sidekick

Sidekick, Junior Sidekick.

[![Kotlin](https://img.shields.io/badge/kotlin-2.3-blue.svg?logo=kotlin)](http://kotlinlang.org)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

Sidekick is a multiplayer AI assistant for AI-pilled teams. It listens in Slack or Discord, runs an LLM-powered agent, and replies in DMs, channels, and threads.

## Overview

Sidekick is built for teams that want an assistant where the work already happens: Slack (or, for some, Discord).

Ask it to investigate a problem, work with a repository, query connected systems, or run recurring tasks. Sidekick keeps conversation history, uses channel-specific project instructions, and runs tools to get work done.

## Your Systems, Your Control

- Implement custom tools for any company resource or API, without a fixed integration catalog.
- Build your own UI through a public API or a custom interface adapter.
- Run Sidekick on-prem, with control over data storage, tool execution, and internal integrations. Core application features run within your deployment; model inference currently uses OpenRouter.

## Key Features

### Team Conversations

- Slack and Discord conversations with persistent history and automatic context compaction.
- Selective thread participation and unsubscribe commands.
- Mid-task follow-ups, live todo lists, and Slack's Stop control.

### Getting Work Done

- Workspace files, web pages, and conversation history.
- Git repositories, including private GitHub and Bitbucket repos.
- Sandboxed Bash. Remote project-user execution is experimental.
- Attachments and Slack files, canvases, and reactions.
- Isolated subagents with up to two levels of delegation.
- Recurring jobs managed through Slack.

### Context and Integrations

- Project instructions (`AGENTS.md`), project tags, and shared company knowledge.
- Extensible skills, subagents, and custom tools.
- External MCP servers with static credentials or OAuth.
- OpenRouter model profiles and optional Jev reply classification.

## Configuration

- [Set up Slack](SETUP_SLACK.md)
- [Set up Discord](SETUP_DISCORD.md)

## Privacy & Security

### Conversations

Replies in shared channels are visible to external members and can expose internal data. Workspace-based access rules are in progress and not yet enforced.

### Internal Systems Access

Sidekick accesses internal systems as a dedicated service account using configured credentials and API keys, without tying access to the requester's identity. Scope those credentials narrowly; shell sandboxing does not restrict access through connected tools.

## Sandbox

Bash runs through local Bubblewrap or a separate [sandbox service](sandbox-service/README.md). Execution on an external shared VM is experimental.

## Integrations

| Integration | Purpose |
| --- | --- |
| [SamePage](https://github.com/uncomplex-co/dumphere) | Publishes and manages shareable HTML and Markdown artifacts. |

## Ingredients

- Kotlin / Spring Boot
- [Slack Bolt for JVM](https://github.com/slackapi/java-slack-sdk)
- [JDA](https://github.com/discord-jda/JDA)
- [Koog](https://github.com/JetBrains/koog)
- [Bubblewrap](https://github.com/containers/bubblewrap)
- [mise](https://mise.jdx.dev/)

## Status

Sidekick is a research preview, not a polished product. Capabilities vary by chat platform and configuration. Expect sharp edges and frequent changes.

## License

Sidekick is licensed under the [Apache License 2.0](LICENSE).
