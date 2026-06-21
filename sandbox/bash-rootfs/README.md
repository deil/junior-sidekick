# Bash Sandbox Rootfs

Build the root filesystem used by the Sidekick bash tool:

```bash
sandbox/bash-rootfs/build-rootfs.sh
```

Default output:

```text
data/sandbox/bash-rootfs
```

Point Sidekick at it:

```properties
agent.tools.bash.enabled=true
agent.tools.bash.rootfs=./data/sandbox/bash-rootfs
```

Control installed CLIs by editing `Dockerfile`.

Optional overrides:

```bash
IMAGE_NAME=my-rootfs OUTPUT_DIR=/tmp/sidekick-rootfs sandbox/bash-rootfs/build-rootfs.sh
```
