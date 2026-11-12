# Sandbox v2 filesystem

Status: partially implemented. Local filesystem extraction and remote adapter wiring are complete. Remote filesystem operations, project-template seeding, and the remaining integrations are not implemented. Project-user execution remains highly experimental and incomplete.

## Problem

With `agent.tools.bash.provider=http-v2`, Bash runs on a remote VM. Git tools, project-context loading, skills, and attachment handling still depend on Sidekick's local filesystem. File tools now select a remote adapter when v2 Bash is enabled, but its filesystem client methods are stubs. They fail rather than fall back to local files.

The local and remote filesystems must not be treated as interchangeable. In particular, the local virtual path `/data/project` does not exist on the VM.

## Direction

Keep project working files on the VM. Route agent-facing filesystem operations through the sandbox API when v2 is configured. Transfer inputs and outputs explicitly; do not bidirectionally synchronize project directories.

| Files | Location | Preparation or transfer |
| --- | --- | --- |
| Project working files and command outputs | Project user's VM home, under `data/project/` | Seed project templates; migrate existing files explicitly; download outputs for delivery |
| Project templates | Sidekick's `templates/project/` | Copy missing files into the VM project directory without overwriting existing files |
| Session scratch files | VM, under `~/sessions/<session-id>/work/` | Thread-scoped; no synchronization back to Sidekick |
| Incoming attachments | Alongside conversation history; VM copy for tool access | Upload immediately after recording into `~/sessions/<session-id>/attachments/` |
| Reply attachments | Alongside conversation history; source file in workspace | Download a snapshot from the workspace for recording and delivery |
| Skills repositories | Shared VM `/data/skills/`; local checkouts for Sidekick discovery and prompts | Sandbox daemon clones and refreshes configured repositories on the VM |
| Knowledge repositories | Shared VM `/data/global/`; local checkouts for Sidekick | Sandbox daemon clones and refreshes configured repositories on the VM |

Background processes can modify working files between commands. Keeping those files authoritative on the VM avoids competing local copies and synchronization conflicts. Shared network storage is not required.

## Backend selection

- `bwrap` and `http` retain the existing local filesystem behavior.
- Bash enabled with `http-v2` selects a remote filesystem implementation. With Bash disabled, filesystem operations remain local.
- Tools use a common filesystem interface. Provider selection belongs in wiring, not capability flags or provider checks inside tools.
- Remote operations receive the current project's ID as a mandatory method argument, supplied from trusted execution context rather than model arguments.
- Remote operations run as the project's Linux user.
- `RemoteWorkspaceFilesystem` and `RemoteSandboxV2Executor` delegate to the shared `SandboxV2Client`. Its v2 Bash execution is implemented; its filesystem methods are not.

Sidekick's configuration, persisted sessions, and other internal state remain local. This change concerns agent-facing workspace operations, not every filesystem access in Sidekick.

## Paths and permissions

- V2 project files live in `~/data/project/`. The home-relative path `data/project/...` replaces the v1 virtual path `/data/project/...`.
- Keep the project user's home as the default Bash command directory and the base for relative filesystem paths. A previous Bash command's working directory does not change that base.
- Resolve the project home through the sandbox daemon. Sidekick must not duplicate its Linux username or home-path calculation.
- `/data/global/` and `/data/skills/` remain shared absolute directories on the VM. They are real daemon-managed directories, not host mounts or per-project aliases.
- All managed `sku_*` users need directory traversal and file-read access to those shared repositories, but no write access. The daemon manages clone and refresh permissions separately.
- Session-specific uploads belong under `~/sessions/<session-id>/attachments/`; writable scratch and generated files belong under `~/sessions/<session-id>/work/`.
- The session ID is thread-scoped and supplied from trusted conversation context. These directories are separate from `~/data/project/` and `.agents/`.

Here, `~` denotes the project user's home. Filesystem requests must use the agreed home-relative or daemon-resolved absolute paths; shell tilde expansion must not be assumed for API paths.

There are no mounts in v2. Do not recreate host mounts, create a global `/data/project` alias, or invent a separate filesystem root per project.

## Project templates and AGENTS.md

The existing local setup supports optional templates under `${agent.working-directory}/templates/project/`. There is no checked-in `AGENTS.md` template. Existing project files are not overwritten, and all threads in a channel share the same project workspace.

For v2, copy the project template into `~/data/project/` before loading project context or running tools. Copy missing files and create their parent directories; preserve existing files, including `AGENTS.md`. Template preparation must be safe to repeat.

Project-context loading must read `~/data/project/AGENTS.md` through the selected filesystem backend and retain the existing per-turn snapshot behavior. A missing file means empty project context. Remote failures must not silently substitute local instructions.

The next implementation slice covers project templates only. Session work-template copying is deferred.

## Skills and knowledge repositories

Knowledge repositories are configured in `config/knowledge.json`; skills repositories are configured in `config/extensions.json`. Both are cloned and refreshed, not created from templates.

For v2, Sidekick requests the sandbox daemon to clone or refresh these repositories from inside the VM. Do not upload Sidekick's local checkout directories. Sidekick still needs its local checkouts for discovery and prompt assembly; agreement between local and VM revisions remains to be specified.

Private-repository operations need an authentication mechanism that keeps credentials outside the sandbox boundary. Do not store credentials inside the VM, where model-driven commands could discover them. Sidekick-local SSH-key paths cannot be passed as though they identify files there.

## Migration from v1

- Existing local project files need an explicit transfer into the VM project directory, separate from template seeding.
- Existing `AGENTS.md`, scheduled-job prompts, scripts, and other project files may reference `/data/project`. Those references need migration to home-relative `data/project` or the resolved VM project path.
- Do not silently rewrite arbitrary command strings or scripts. The migration process and review rules remain open.
- `/data/global` and `/data/skills` can retain their paths once the daemon has prepared the corresponding shared VM checkouts.
- Incoming attachment paths, session scratch paths, and references persisted in session history also need consideration. `/work` and `/data/session` must not be advertised as available VM paths unless explicitly provided there.
- `bwrap` and legacy `http` retain their existing virtual roots and mounts.

## Required integration

`WorkspaceFilesystem` exposes session-scoped binary transfers:

- `upload(projectId, sessionId, source: Path): WorkspaceFile` copies a Sidekick-local file into the session's attachments directory, retaining its filename. It returns the agent-visible workspace path and destination metadata. The caller does not choose a workspace destination.
- `download(projectId, sessionId, source: String, destination: Path): FileMetadata` copies a session-relative source, such as `work/report.pdf` or `attachments/input.pdf`, into Sidekick-local storage. Arbitrary workspace paths are not accepted.
- Both IDs come from trusted execution context. Workspace path construction belongs in the adapter. Local adapters are already bound to a conversation's `VirtualPaths`: `attachments/` maps to `/data/session/`, and `work/` maps to `/work/`. Remote adapters use `~/sessions/<session-id>/`.
- Local transfers copy regular files, create missing parent directories, preserve source files, and do not overwrite existing destinations. An upload whose source already is the local session attachment leaves that file unchanged.
- Remote transfers are stubs; no HTTP transfer contract is implemented, and local paths must not be serialized into sandbox requests.

Incoming attachments are recorded alongside conversation history before being immediately copied into the workspace. Reply attachments are copied from the workspace into conversation-owned storage for recording and delivery. Transfer methods own workspace path construction, but do not own conversation records or choose the conversation-storage download destination. Attachment-flow integration, including durable reply-file retention and message linkage, remains unimplemented.

- File read, glob, and grep must see the same files as Bash.
- Git operations must use the remote project files.
- Project-template preparation must finish before project-context loading reads the authoritative `AGENTS.md`.
- Reply attachments must be downloadable from the VM.
- Incoming attachments must be uploaded before tools need them. The daemon must prepare skills and knowledge checkouts before their paths are advertised.
- Paths exposed in prompts and tool results must resolve on the filesystem those tools use. Do not advertise local `/data/...` paths as remote paths unless they are provided there.

## Implementation order

1. Completed: extract local workspace operations behind `WorkspaceFilesystem`.
2. Completed: share the v2 sandbox client between Bash and the remote filesystem adapter, and wire backend selection. Remote filesystem methods remain stubs.
3. Add daemon-side project-home resolution and the remote operations needed to seed project templates into `~/data/project/` without overwriting files.
4. Route project-context loading through the selected backend.
5. Implement the remaining remote filesystem operations and daemon-managed skills/knowledge repository preparation.
6. Add attachment uploads and output downloads; route Git and reply attachment delivery to the remote project files.
7. Update prompt and skill path generation, and provide an explicit migration for existing files and path references.

## Open decisions

- Remote file-operation and transfer contracts.
- VM repository checkout naming, local/remote revision agreement, and refresh/deletion rules for skills and knowledge.
- Credential handling for daemon-managed private repository clones and tools such as `git__clone` and `git__push`. These operations must work with VM project files while credentials remain outside the sandbox boundary, never stored inside the VM or exposed to model-driven commands. The authentication and transport mechanism remains open.
- Migration of existing local project files, scheduled-job prompts, and persisted path references.

Existing execution behavior is documented in [bash.md](bash.md). This document distinguishes the implemented filesystem seam and remote wiring from the remaining v2 work.
