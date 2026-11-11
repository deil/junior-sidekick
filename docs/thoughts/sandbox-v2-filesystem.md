# Sandbox v2 filesystem

Status: planned, not implemented. Project-user execution remains highly experimental and incomplete.

## Problem

With `agent.tools.bash.provider=http-v2`, Bash runs on a remote VM while file tools, Git tools, and project-context loading still access Sidekick's local filesystem. Files created by one are invisible to the other. Local virtual paths such as `/data/project` do not exist on the VM.

## Direction

Keep project working files on the VM. Route agent-facing filesystem operations through the sandbox API when v2 is configured. Transfer inputs and outputs explicitly; do not bidirectionally synchronize project directories.

| Files | Authoritative location | Transfer |
| --- | --- | --- |
| Project working files and command outputs | VM | Download when needed for delivery |
| Session scratch files | VM | No synchronization back to Sidekick |
| Incoming attachments | Sidekick | Upload for the relevant session |
| Skills and global knowledge | Sidekick | One-way upload and refresh |

Background processes can modify working files between commands. Keeping those files authoritative on the VM avoids competing local copies and synchronization conflicts. Shared network storage is not required.

## Backend selection

- `bwrap` and `http` retain the existing local filesystem behavior.
- `http-v2` selects a remote filesystem implementation.
- Tools use a common filesystem interface. Provider selection belongs in wiring, not capability flags or provider checks inside tools.
- Remote operations receive the current project's ID as a mandatory method argument, supplied from trusted execution context rather than model arguments.
- Remote operations run as the project's Linux user.

Sidekick's configuration, persisted sessions, and other internal state remain local. This change concerns agent-facing workspace operations, not every filesystem access in Sidekick.

## Required integration

- File read, glob, and grep must see the same files as Bash.
- Git operations must use the remote project files.
- Project-context loading must read `AGENTS.md` from the authoritative project directory.
- Reply attachments must be downloadable from the VM.
- Incoming attachments, skills, and knowledge must be uploaded before tools need them.
- Paths exposed in prompts and tool results must resolve on the filesystem those tools use. Do not advertise local `/data/...` paths as remote paths unless they are provided there.

Keep the project user's home as the default command directory. Do not recreate host mounts or invent a separate filesystem root per project.

## Implementation order

1. Put existing local workspace operations behind the filesystem interface without changing behavior.
2. Add the sandbox API implementation and select it for v2.
3. Add input uploads and output downloads.
4. Route Git, project-context loading, and attachment delivery through the same backend.
5. Update prompt and skill path generation to describe files available on the VM.

## Open decisions

- Remote file-operation and transfer contracts.
- Directory layout for uploaded inputs and session-scoped files.
- Refresh and deletion rules for uploaded skills and knowledge.
- Migration of existing local project files to the VM.

Current execution behavior is documented in [bash.md](../context/bash.md); the filesystem routing described here is future work.
