# Bash Sandbox Rootfs

Build the root filesystem used by the Sidekick bash tool:

```bash
sandbox/bash-rootfs/build-rootfs.sh
```

Default output:

```text
data/sandbox/bash-rootfs
```

Point the direct local `bwrap` provider at it:

```properties
agent.tools.bash.enabled=true
agent.tools.bash.provider=bwrap
agent.tools.bash.bwrap.rootfs=./data/sandbox/bash-rootfs
```

For the HTTP provider, rootfs is configured on `sandbox-service`, not in Sidekick:

```hocon
sandbox {
  rootfs = "../data/sandbox/bash-rootfs"
}
```

Control installed CLIs by editing `Dockerfile`.

Optional overrides:

```bash
IMAGE_NAME=my-rootfs OUTPUT_DIR=/tmp/sidekick-rootfs sandbox/bash-rootfs/build-rootfs.sh
```
