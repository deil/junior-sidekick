# Sandbox v2 filesystem

Status: partially implemented. Bash execution, filesystem operations, Git operations, project seeding, and session transfers have interfaces and local implementations. Interface extraction is not complete: workspace addressing, session preparation, shared repositories, MCP output storage, and extension tools still depend on local paths or adapter implementations. Remote Bash execution works; remote filesystem, Git, seeding, and transfer operations remain stubs. Project-user execution remains highly experimental and incomplete.

## Problem

With `agent.tools.bash.provider=http-v2`, Bash runs on a remote VM. File and Git tools select remote adapters, but those operations are stubs and fail rather than fall back to local files. Project-context loading, skills, attachment handling, and several other consumers still assume Sidekick-local files.

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
- Application and domain code depend on ports, never adapter implementations. Template discovery and configuration belong to callers; filesystem adapters receive explicit inputs and own backend-specific paths.
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

Project preparation now calls `workspace.transfers.seedProject(projectId, source: Path)` through the selected backend. The caller resolves the optional template directory; the filesystem implementation receives only that source directory and owns the project destination. It has no knowledge of template configuration or storage layout. Local seeding copies the directory contents, retains nested paths and file attributes, and skips existing files. Repeated preparation can add missing files without replacing project edits. Work-template copying retains its existing local behavior. Remote project seeding currently throws `NotImplementedError`; it does not fall back to local copying.

For v2, copy the project template into `~/data/project/` before loading project context or running tools. Copy missing files and create their parent directories; preserve existing files, including `AGENTS.md`. Template preparation must be safe to repeat.

Project-context loading must read `~/data/project/AGENTS.md` through the selected filesystem backend and retain the existing per-turn snapshot behavior. A missing file means empty project context. Remote failures must not silently substitute local instructions.

Session work-template copying and scratch-directory creation are still local operations. They need a session-preparation interface before remote implementation; the existing local folder layout and copying behavior must remain unchanged.

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

`Workspace.transfers` exposes binary transfers:

- `upload(projectId, sessionId, source: Path): WorkspaceFile` copies a Sidekick-local file into the session's attachments directory, retaining its filename. It returns the agent-visible workspace path and destination metadata. The caller does not choose a workspace destination.
- `download(projectId, sessionId, source: String, destination: Path): FileMetadata` copies a session-relative source, such as `work/report.pdf` or `attachments/input.pdf`, into Sidekick-local storage. Arbitrary workspace paths are not accepted.
- `download(projectId, source: String, destination: Path): FileMetadata` copies an allowed workspace file into Sidekick-local storage. The caller passes the agent-supplied source path unchanged; the selected adapter resolves and validates it. This overload does not address session storage or require `sessionId`.
- Both IDs come from trusted execution context. Workspace path construction belongs in the adapter. Local adapters are already bound to a conversation's `VirtualPaths`: `attachments/` maps to `/data/session/`, and `work/` maps to `/work/`. Remote adapters use `~/sessions/<session-id>/`.
- Local transfers copy regular files, create missing parent directories, preserve source files, and do not overwrite existing destinations. An upload whose source already is the local session attachment leaves that file unchanged.
- Remote transfers are stubs; no HTTP transfer contract is implemented, and local paths must not be serialized into sandbox requests.

The target incoming flow records attachments alongside conversation history and immediately copies them into the workspace. The target reply flow copies attachments from the workspace into conversation-owned storage for recording and delivery. Transfer methods own workspace path construction, but do not own conversation records or choose the conversation-storage download destination. Incoming files already have durable conversation storage, but their workspace upload is not wired yet. Reply files now download through the filesystem port into temporary local staging and are deleted after delivery; durable reply-file retention and message linkage remain unimplemented.

The `attachFile` tool uses the project-scoped download overload to retain access to files anywhere in the allowed workspace, including project files. Its collector validates file kind and the 10 MiB limit through `stat`, downloads a local snapshot into conversation reply staging, and retains the five-files-per-reply limit and existing delivery cleanup. Workspace reads use the filesystem port; conversation staging and delivery remain local. No `copyToDropbox` operation or extra VM staging step is required. The session-scoped overload retains its narrower source semantics.

- File read, glob, and grep must see the same files as Bash.
- Git operations must use the remote project files.
- Project-template preparation must finish before project-context loading reads the authoritative `AGENTS.md`.
- Reply attachments must be downloadable from the VM.
- Incoming attachments must be uploaded before tools need them. The daemon must prepare skills and knowledge checkouts before their paths are advertised.
- Paths exposed in prompts and tool results must resolve on the filesystem those tools use. Do not advertise local `/data/...` paths as remote paths unless they are provided there.

## Interface extraction

Extract interfaces and migrate local consumers before implementing more remote operations. Interface extraction, consumer migration, and remote implementation are separate milestones.

### Extracted

- `SandboxExecutor`: Bash execution, with local/legacy and v2 backend selection.
- `Workspace.search`: stat, directory listing, glob, and grep. `Workspace.files`: text read/write/edit and patch.
- `WorkspaceGit`: clone, push, and pull, with local execution and a remote stub.
- `Workspace.transfers.seedProject(projectId, source)`: caller-selected source directory; adapter-owned project destination. Local preparation uses this interface.
- `Workspace.transfers.upload` and `download`: session-scoped binary transfers, with local implementations and remote stubs.
- `Workspace.transfers.download(projectId, source, destination)`: workspace-file export, used by `attachFile`, with local implementation and a remote stub. `ReplyAttachmentCollector` receives the selected workspace through its port and no longer resolves workspace files locally.
- `WorkspaceFilesystemFactory`: a port; filesystem construction and selection live in adapter wiring.

### Remaining interfaces and local-path contracts

| Area | Current dependency | Required extraction |
| --- | --- | --- |
| Workspace addressing | `VirtualPaths` exposes local `Path` objects; project instructions, attachments, scratch files, and skill locations are derived from them | Backend-independent project/session addressing and agent-visible locations, without caller-side local/remote path branching |
| Session preparation | `VirtualPathsFactory` creates local scratch directories; `Hooks.kt` copies work templates locally | A session-preparation operation whose implementation owns workspace destinations; callers retain template-source discovery |
| Shared repository preparation | `Skills` and `GlobalWorkspace` call `gitRepositoryCheckoutPath` and `syncGitRepository` adapters directly; `Subagents` also calls the checkout-path adapter helper | Ports for repository preparation and logical checkout references; retain local discovery copies and prepare VM checkouts separately |
| MCP output storage | `McpServersRegistry.connect` takes `workRoot: Path`; `ConnectedMcpServer` exposes `ToolRegistration<Path>`; `McpServerTool` writes oversized output locally | A session-output storage interface or session-aware filesystem operation returning an agent-visible file reference |
| Extension-tool file access | `SidekickToolContext.resolvePath` returns a local Java `Path`; DumpHere reads files from that path | A file-access/export contract that supports both backends without exposing local workspace paths |
| Remaining adapter dependencies | `DefaultAgentToolCatalog` imports concrete `WorkspaceGitFactory` and `SandboxExecutorFactory`; application code imports the adapter-level conversation `folder` helper | Port-based factories and application-owned logical references; keep physical storage layout inside its implementation |

### Consumers still bypassing the extracted interfaces

- `ProjectContexts` reads `AGENTS.md` with Java `Files`; `SessionManager` supplies a local project root. Separate project-file access from parsing, tags, and template rendering, retaining per-turn snapshots.
- `ChatFileIngestor` records incoming files locally but does not upload them. Keep the conversation-owned copy and add immediate workspace transfer after recording.
- Reply-file recording still needs durable conversation storage and message linkage. `ReplyAttachmentCollector` already uses the filesystem port for workspace validation/download, but staged reply files are still deleted after delivery.
- `SkillTools` reads local skill files and resources and advertises paths through `VirtualPaths`. Local discovery and prompt assembly can remain local; executable/resource locations must refer to the selected workspace.
- Skill, attachment, and delegated-agent prompt generation still uses local virtual paths. Return workspace references from the interfaces rather than reconstructing paths in prompts.

Application configuration, persisted conversation history, attachment archives, checkpoints, and OAuth credentials remain Sidekick-local. Their locality alone does not require a remote workspace interface.

## Implementation order

1. Complete workspace-addressing and session-preparation interfaces; preserve local paths and work-template behavior.
2. Remove remaining application-to-adapter dependencies, including Git/Bash factories and repository/layout helpers.
3. Extract shared repository preparation, session-output storage for MCP, and backend-independent file access for extension tools.
4. Migrate project-context loading, incoming/reply attachments, skills, MCP, extension tools, and prompt generation to those interfaces, using local implementations first. Preserve existing behavior except the explicitly planned durable reply-attachment recording.
5. Implement daemon-side project-home resolution, project seeding, and filesystem operations. Ensure seeding finishes before remote `AGENTS.md` loading and never substitute local instructions after remote failure.
6. Implement remote session preparation, uploads, both download overloads, and daemon-managed skills/knowledge checkouts. Complete immediate incoming uploads and durable reply-file recording/delivery.
7. Implement remote Git after credential transport is agreed; credentials remain outside the sandbox boundary.
8. Provide explicit migration of existing files and path references. Verify that prompts and tools refer to the same VM files as Bash.

Each extraction must keep the full test suite passing, retain local folder structure and behavior, and test observable file contents and side effects through the public interface. Remote stubs must fail rather than fall back to local files. Do not replace behavioral assertions with implementation/wiring checks.

## Open decisions

- Whether remote project instructions remain at `~/data/project/AGENTS.md` or move to `~/AGENTS.md`. The current specified layout remains unchanged until a move is agreed.
- Workspace-addressing and session-preparation interface shapes, including session work-template seeding.
- Replacement of local-`Path` contracts in extension tools and MCP output storage.
- Remote file-operation and transfer contracts.
- VM repository checkout naming, local/remote revision agreement, and refresh/deletion rules for skills and knowledge.
- Credential handling for daemon-managed private repository clones and tools such as `git__clone` and `git__push`. These operations must work with VM project files while credentials remain outside the sandbox boundary, never stored inside the VM or exposed to model-driven commands. The authentication and transport mechanism remains open.
- Migration of existing local project files, scheduled-job prompts, and persisted path references.

Existing execution behavior is documented in [bash.md](bash.md). This document distinguishes the implemented filesystem seam and remote wiring from the remaining v2 work.
