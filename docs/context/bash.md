# Bash Tool Sandbox

Sidekick can expose a native `bash` tool when `agent.tools.bash.enabled=true`.

The tool delegates execution through a sandbox executor port. Phase 1 supports the direct local `bwrap` provider with `agent.tools.bash.provider=bwrap`; the default provider is reserved for the external HTTP sandbox service.

The standalone `sandbox-service` module exposes `POST /api/execute` for provider-backed execution. It authenticates with a bearer token, validates requested mount sources against service-side allowed prefixes, then executes through the reusable `sandbox-bwrap` module.

Sidekick selects the bash sandbox provider with `agent.tools.bash.provider`. Use `http` for the external sandbox service and `bwrap` for direct local execution.

## Root Filesystem

`agent.tools.bash.bwrap.rootfs` points to the controlled filesystem tree mounted read-only as `/` inside the sandbox when using the direct `bwrap` provider.

This rootfs defines which CLIs are available. If `/bin/bash`, dynamic linker, libc, or a requested CLI is missing from the rootfs, the command fails. Sidekick does not mount the host `/bin`, `/usr`, `/lib`, `/data/global`, `/data/skills`, or session state into the sandbox.

Build the default rootfs with `sandbox/bash-rootfs/build-rootfs.sh`. Edit `sandbox/bash-rootfs/Dockerfile` to control installed CLIs.

## Available Tools

The sandbox tool inventory is defined by `sandbox/bash-rootfs/Dockerfile`, not by Sidekick runtime code.

The default rootfs currently installs general shell utilities, network/DNS diagnostics, Git, Node/npm, and Python/pip. Treat the Dockerfile as the source of truth for exact package names and versions.

When a new CLI is needed, add the package to the Dockerfile and rebuild the rootfs. Do not mount host tool directories into the sandbox.

Notable default command families:

- shell/core text tools: `bash`, GNU coreutils, `find`, `grep`, `sed`, `gawk`, `less`, archive tools
- network/DNS tools: `curl`, `ping`, `host`, `nslookup`, `dig`, `whois`
- development tools: `git`, `node`, `npm`, `python3`, `pip`
- data tools: `jq`

## Mounts

The sandbox mounts only:

- configured rootfs read-only at `/`
- conversation-scoped work directory read-write at `/work`
- channel-scoped project workspace read-write at `/data/project`
- fresh tmpfs at `/tmp`
- sandbox `/proc`
- sandbox `/dev`

The work directory is scoped to the current conversation. The project directory is scoped to the current Slack channel.

## Sandbox Filesystem Layout

Inside the sandbox:

- `/` is the configured rootfs from `agent.tools.bash.bwrap.rootfs` for the direct `bwrap` provider, mounted read-only.
- `/data/session` is the current conversation attachments directory, mounted read-only.
- `/data/skills` is the skills directory, mounted read-only.
- `/data/global` is the global workspace directory, mounted read-only.
- `/work` is the current conversation's durable bash work directory under `${agent.working-directory}/threads/<conversation-id>`, mounted read-write.
- `/data/project` is `${agent.working-directory}/projects/<channel-id>`, mounted read-write.
- `/tmp` is an empty tmpfs for the command run.
- `/proc` is procfs for the sandbox PID namespace.
- `/dev` is a minimal sandbox device filesystem.
- `/etc/resolv.conf` comes from the generated rootfs and must be present for DNS when network is enabled.

Commands should use `/work` for session-scoped files that must survive beyond a single command and `/data/project` for durable channel-scoped project workspace changes. Files written elsewhere either fail because the rootfs is read-only or disappear with tmpfs/sandbox teardown.

The bash tool `workdir` parameter is a sandbox path, not a host path. It defaults to `/`; relative values are resolved from the sandbox root. The directory must already exist inside the sandbox.

## Network

`agent.tools.bash.network-enabled=false` creates a separate network namespace for commands.

`agent.tools.bash.network-enabled=true` keeps network access from the Sidekick container. Do not enable this unless the container network cannot reach credentials, metadata services, internal control planes, or other sensitive services.

For the HTTP sandbox provider, Sidekick sends the configured network policy to `sandbox-service` as `networkEnabled`; it is not an LLM-facing bash tool parameter.

## Sandbox Service

`sandbox-service` is a standalone Ktor application. It does not depend on Sidekick `tools` or `core` modules and does not share HTTP DTO classes with Sidekick.

Production shape: Sidekick runs with `agent.tools.bash.provider=http` and does not need local `bwrap` privileges. `sandbox-service` runs separately in an environment where `bwrap` is supported.

API:

```http
POST /api/execute
Authorization: Bearer <token>
```

Request mount fields use Docker-style names:

- `source` - host path visible to `sandbox-service`
- `target` - absolute sandbox path
- `mode` - `ro` or `rw`

Sidekick HTTP provider config:

```properties
agent.tools.bash.provider=http
agent.tools.bash.http.base-url=http://localhost:7171
agent.tools.bash.http.token=<token>
```

The HTTP provider sends writable virtual roots, including the current conversation bash work directory at `/work` and the channel-scoped project directory at `/data/project`, as dynamic `rw` mounts.

When `agent.tools.bash.scratch-gid` is set, Sidekick prepares writable sandbox roots with that host GID and mode `2770` before requesting sandbox mounts. Configure `sandbox.gid` to the same GID so the sandbox process can write to `/work` and `/data/project` while keeping its non-root UID.

Create a shared host group for Sidekick and the sandbox service, then use its numeric GID in both configs:

```bash
sudo groupadd --system sidekick-sandbox
getent group sidekick-sandbox
```

If `getent` prints `sidekick-sandbox:x:997:`, configure Sidekick with `agent.tools.bash.scratch-gid=997`, configure sandbox service with `sandbox.gid=997`, and run the Sidekick Docker container with supplemental group `997`.

The service validates each mount `source` against `sandbox.allowed-source-prefixes` from its Ktor config. Prefix validation belongs to the service boundary, not `sandbox-bwrap`.

Mount `source` paths are interpreted by `sandbox-service`, not by Sidekick. In production, the Sidekick state directory path used for conversation work must be visible to `sandbox-service` at the same path or translated before the HTTP provider sends it.

`sandbox-service` loads Ktor config from `application.conf` by default and supports Ktor's `-config=<file>` option for deployment-specific config. A helper script can register a local user-level systemd service:

```bash
sandbox-service/register-systemd-service.sh
```

## Runtime Policy

Commands run with the configured non-root UID/GID, defaulting to `65534:65534`.

The tool enforces a wall-clock timeout and output byte cap. Resource limits beyond that should be enforced by the container/cgroup that runs Sidekick or a future dedicated runner.

## Key Files

- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/bash/BashTools.kt` - LLM-facing bash tool and dynamic scratch mount assembly.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/ports/sandbox/SandboxExecutor.kt` - Sidekick tool-level sandbox execution port.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/adapters/sandbox/BwrapSandboxExecutor.kt` - direct local bwrap adapter for the sandbox port.
- `sandbox-bwrap/src/main/kotlin/com/github/uncomplexco/sidekick/sandbox/bwrap/BwrapSandbox.kt` - reusable framework-free bwrap executor.
- `sandbox-service/src/main/kotlin/com/github/uncomplexco/sidekick/sandbox/service/SandboxService.kt` - standalone Ktor sandbox executor service.
- `sandbox-service/register-systemd-service.sh` - registers a user-level systemd service for local sandbox-service operation.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/TurnToolRegistryFactory.kt` - registers the tool with the current conversation work directory.
