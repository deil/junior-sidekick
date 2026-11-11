---
type: Domain
---

# Discord

## Todo delivery

Sidekick publishes `Todo` updates in the same reply target as its final response:
the current DM or thread, or a thread created under a guild mention. The first
update posts a message; subsequent updates edit that message. The final response
is separate. Todo messages are not explicitly recorded in session or Koog history.

The tool owns the current goal, task list, and update time. Discord publishes that
snapshot immediately and refreshes its elapsed-time footer every minute while the
turn has unfinished tasks. Completed/cancelled lists omit the age and stop
refreshing. Turn completion also stops refreshes and leaves the message in place.

Delivery is best-effort. A failed initial post can be retried on the next update;
failed edits retain the message reference. Delegates return the structured list
without publishing. Scheduled runs also keep todos in memory without publishing or
refreshing messages. Message text preserves Discord Markdown formatting.

Typing indicators remain separate from todo delivery. Both posting and editing
use the gateway's existing reply-channel resolution and disable automatic mentions.

Published todo text is capped at Discord's 2,000-character message limit. Oversized
messages retain the time/age footer and mark omitted content with a truncation notice.
The tool snapshot and JSON result remain complete. The cap applies to posts, edits,
and minute refreshes.
