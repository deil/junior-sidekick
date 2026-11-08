# JavaScript bridge

JavaScript/TypeScript packages live under this pnpm workspace root, with one
subfolder per module. Node.js and pnpm versions are defined in `../mise.toml`.

## Chat UI prompt

`chat-ui/catalog.mjs` uses the complete standard catalog from `@json-render/chat`.
`chat-ui/print-prompt.mjs` writes `catalog.prompt({ mode: "inline" })` to stdout.
The library's inline format combines prose with fenced `spec` JSONL blocks.

Regenerate from the repository root:

```sh
./gradlew :core:generateChatUiPrompt
```

The task installs locked bridge dependencies and writes UTF-8 Markdown to
`core/src/main/resources/prompts/chat-ui.md`. Commit that file together with
generator, dependency, and lockfile changes. Generation is explicit: normal JVM
builds package the checked-in resource without invoking Node.js or pnpm.

Kotlin callers use `Prompts.chatUiPrompt()` to read the classpath resource. This
does not insert the prompt into active conversations; local instructions are in
the working directory's `config/RULES.md`.

After upgrading json-render, update both pinned package versions and the lockfile,
run the Gradle task, and review the generated Markdown diff.

## Reply rendering

Slack and Discord delivery post-process the completed reply. Each fenced `spec`
block is compiled with json-render's batch compiler and passed through `toCard`,
then converted to native platform payloads by Chat SDK. Other code fences and
surrounding prose are preserved. There is no streaming.

Slack embeds the blocks and prose in one message, followed by the existing status
line. Discord sends prose and cards in order to the existing reply channel/thread.
Discord uses the adapter's embed format; charts use its text fallback, not a native
chart. Invalid specs become `[Visualization could not be rendered.]`, with the
validation error on stderr. This does not add button interaction handlers.

When rendering is enabled, Node.js and installed bridge dependencies must be
available. From `js-bridge/`, run `pnpm install --frozen-lockfile`. Enable it in
Spring configuration; the other values below show their defaults and paths are
relative to the Sidekick process's working directory:

```properties
agent.chat-ui.enabled=true
agent.chat-ui.node=node
agent.chat-ui.bridge-directory=./js-bridge
```

The experiment is disabled by default. Set `agent.chat-ui.enabled=true` in your
Spring configuration to enable rendering on both Slack and Discord. Set it to
`false` to send replies unchanged without launching Node.js. Restart Sidekick
after changing the setting. Wrap chat UI instructions in `config/RULES.md` with
`{% if features.chat_ui %}`, an optional `{% else %}`, and `{% endif %}`.
Pebble selects the matching branch before sending instructions to the model.
See `examples/RULES.md` for enabled and disabled instructions.

The renderer launches only for replies containing a `spec` fence. It receives the
completed reply through stdin and returns JSON through stdout; it receives no chat
credentials and does not inherit the Sidekick process environment.
