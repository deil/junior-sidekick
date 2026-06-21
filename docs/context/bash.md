# Bash Tool Sandbox

Sidekick can expose a native `bash` tool backed by `bubblewrap` when `agent.tools.bash.enabled=true`.

## Root Filesystem

`agent.tools.bash.rootfs` points to the controlled filesystem tree mounted read-only as `/` inside the sandbox.

This rootfs defines which CLIs are available. If `/bin/bash`, dynamic linker, libc, or a requested CLI is missing from the rootfs, the command fails. Sidekick does not mount the host `/bin`, `/usr`, `/lib`, `global:/`, `skills:/`, or session state into the sandbox.

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
- conversation-scoped scratch directory read-write at `/work`
- fresh tmpfs at `/tmp`
- sandbox `/proc`
- sandbox `/dev`

The scratch directory lives under the current conversation folder at `scratch/bash` and is the only durable writable area exposed to commands.

## Sandbox Filesystem Layout

Inside the sandbox:

- `/` is the configured rootfs from `agent.tools.bash.rootfs`, mounted read-only.
- `/work` is the current conversation's durable bash scratch directory, mounted read-write.
- `/tmp` is an empty tmpfs for the command run.
- `/proc` is procfs for the sandbox PID namespace.
- `/dev` is a minimal sandbox device filesystem.
- `/etc/resolv.conf` comes from the generated rootfs and must be present for DNS when network is enabled.

Sidekick workspace roots are not mounted. Paths like `global:/`, `skills:/`, and `session:/` are virtual paths for Sidekick tools, not shell paths inside bash.

Commands should use `/work` for any files that must survive beyond a single command. Files written elsewhere either fail because the rootfs is read-only or disappear with tmpfs/sandbox teardown.

The bash tool `workdir` parameter is a sandbox path, not a host path. It defaults to `/`; relative values are resolved from the sandbox root. The directory must already exist inside the sandbox.

## Network

`agent.tools.bash.network-enabled=false` creates a separate network namespace for commands.

`agent.tools.bash.network-enabled=true` keeps network access from the Sidekick container. Do not enable this unless the container network cannot reach credentials, metadata services, internal control planes, or other sensitive services.

## Runtime Policy

Commands run with the configured non-root UID/GID, defaulting to `65534:65534`.

The tool enforces a wall-clock timeout and output byte cap. Resource limits beyond that should be enforced by the container/cgroup that runs Sidekick or a future dedicated runner.

## Key Files

- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/bash/BashTools.kt` - bash tool configuration, `bwrap` argv construction, and output capture.
- `tools/src/main/kotlin/com/github/uncomplexco/sidekick/application/tools/TurnToolRegistryFactory.kt` - registers the tool with the current conversation scratch directory.
