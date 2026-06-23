# Sandbox Service

Standalone Ktor service that exposes a generic bash sandbox executor over HTTP.

## API

```http
POST /api/execute
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "command": "pwd",
  "workdir": "/",
  "timeoutSeconds": 120,
  "networkEnabled": false,
  "mounts": [
    {
      "source": "/srv/sidekick/data/state/example/scratch/bash",
      "target": "/work",
      "mode": "rw"
    }
  ]
}
```

Response:

```json
{
  "ok": true,
  "exitCode": 0,
  "timedOut": false,
  "outputTruncated": false,
  "output": "/\n",
  "workdir": "/"
}
```

## Config

Default config lives in `src/main/resources/application.conf`:

```hocon
ktor {
  deployment {
    port = 7171
  }
  application {
    modules = [com.github.uncomplexco.sidekick.sandbox.service.SandboxServiceKt.module]
  }
}

sandbox {
  token = "test-token-6f3a9b7c2d1e4f80"
  bwrap-path = "bwrap"
  rootfs = "../data/sandbox/bash-rootfs"
  max-output-bytes = 51200
  uid = 65534
  gid = 65534
  allowed-source-prefixes = ["../data"]
}
```

Every requested mount `source` must resolve under one of `sandbox.allowed-source-prefixes`.

## Run

```bash
./gradlew :sandbox-service:run
```

Use another config file with Ktor's `-config` option:

```bash
./gradlew :sandbox-service:run --args="-config=/etc/sidekick/sandbox-service.conf"
```

## systemd

Register a user-level systemd service for the local checkout:

```bash
sandbox-service/register-systemd-service.sh
systemctl --user start junior-sidekick-sandbox.service
journalctl --user -u junior-sidekick-sandbox.service -f
```

Override the service name or config file:

```bash
SERVICE_NAME=sidekick-sandbox \
CONFIG_FILE=/etc/sidekick/sandbox-service.conf \
sandbox-service/register-systemd-service.sh
```

The generated service runs `./gradlew :sandbox-service:run --args=-config=<config-file>` from the repository root. For a packaged deployment, use the same Ktor `-config=<file>` option with the packaged application command.

## Notes

- The service is generic and does not depend on Sidekick `tools` or `core` modules.
- The service owns mount source-prefix validation.
- The reusable `sandbox-bwrap` module owns bwrap process execution.
- The service must run in an environment where `bwrap` is supported.
- Sidekick and `sandbox-service` must agree on mount `source` paths. A path sent by Sidekick must refer to the same filesystem location from the sandbox-service process.
