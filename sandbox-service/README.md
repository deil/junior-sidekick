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

## Notes

- The service is generic and does not depend on Sidekick `tools` or `core` modules.
- The service owns mount source-prefix validation.
- The reusable `sandbox-bwrap` module owns bwrap process execution.
- The service must run in an environment where `bwrap` is supported.
