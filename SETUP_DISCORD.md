# Set up Discord

This guide connects Sidekick to one Discord bot. Sidekick runs either Slack or Discord, not both, in one application instance.

## Create the bot

1. Open the [Discord Developer Portal](https://discord.com/developers/applications).
2. Select **New Application** and enter a name.
3. Open **Bot**.
4. Select **Reset Token**, then copy the token. Discord shows it once.
5. Under **Privileged Gateway Intents**, enable **Message Content Intent**.

Sidekick needs Message Content Intent to read follow-up messages that do not mention the bot. Do not enable Presence Intent or Server Members Intent.

Treat the bot token as a password. If it appears in a log, terminal transcript, issue, or chat, reset it in the Developer Portal.

## Install the bot

1. Open **OAuth2**, then **URL Generator**.
2. Select the `bot` scope.
3. Grant these bot permissions:

   - View Channels
   - Send Messages
   - Create Public Threads
   - Send Messages in Threads
   - Read Message History
   - Add Reactions

4. Open the generated URL.
5. Select the server and authorize the installation.

Channel permission overrides can deny permissions granted at the server level. Check the target channel if the bot can respond elsewhere but not there.

## Configure Sidekick

Set these environment variables in the Sidekick process:

```shell
ADAPTERS_CHAT_PLATFORM=discord
ADAPTERS_DISCORD_BOT_TOKEN=YOUR_DISCORD_BOT_TOKEN
ADAPTERS_OPEN_ROUTER_API_KEY=YOUR_OPENROUTER_API_KEY
```

The defaults remain in `app/src/main/resources/META-INF/sidekick-defaults.properties`.

## Start Sidekick

Run this command from the repository root:

```shell
./gradlew :example-app:bootRun
```

The application connects to Discord during startup. An invalid token or a disabled Message Content Intent prevents the Discord Gateway from becoming ready.

## Test the connection

1. Send the bot a direct message. Sidekick should reply in the DM.
2. Mention the bot in a server text channel. Sidekick should add an `:eyes:` reaction and create a public thread from the message.
3. Wait for the reply. Sidekick should post it in the thread, remove `:eyes:`, and add `:white_check_mark:`.
4. Send a clear follow-up request in that thread without mentioning the bot. Sidekick should process it in the same session.
5. Send an unmentioned message in the parent channel. Sidekick should ignore it.

Sidekick can skip acknowledgments and messages directed at other people. Use a clear request when you test passive thread follow-ups.

## Troubleshoot

### Sidekick responds only when mentioned

Enable **Message Content Intent** on the application's **Bot** page, then restart Sidekick. Adding `GatewayIntent.MESSAGE_CONTENT` in code is not enough unless the Developer Portal toggle is also enabled.

### Sidekick replies in a channel but cannot create a thread

Grant **Create Public Threads** and **Send Messages in Threads** in both the server role and the target channel.

### Reactions do not appear

Grant **Add Reactions** and **Read Message History** in the target channel. Reaction failures do not stop Sidekick from posting a reply.

### The bot receives no server messages

Check that the bot can view the channel. Confirm that the installation includes the `bot` OAuth2 scope, then restart Sidekick and inspect the application log.

### The Gateway rejects the connection

Confirm that the token belongs to the selected application. Enable **Message Content Intent** before restarting. Reset the token if Discord reports that it is invalid.
