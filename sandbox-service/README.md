# Sandbox Service

## Sidekick configuration

```properties
agent.tools.bash.enabled=true
agent.tools.bash.provider=http-v2
agent.tools.bash.http.base-url=http://localhost:7171
agent.tools.bash.http.token=<token>
```

## Install helper

Requires Node.js 22+ at `/usr/bin/node`, `sudo`, `useradd`, `getent`, and `pkill`.

```bash
sudo install -d -o root -g root -m 0755 /usr/local/libexec
sudo install -o root -g root -m 0755 sandbox-service/helper/sandbox-helper.cjs /usr/local/libexec/sandbox-helper
sudo visudo -f /etc/sudoers.d/sandbox-service
```

Grant the daemon's service account access:

```sudoers
sandbox-service ALL=(root) NOPASSWD: /usr/local/libexec/sandbox-helper
```

```bash
sudo -n /usr/local/libexec/sandbox-helper user:ensure --project C123
sudo -n /usr/local/libexec/sandbox-helper bash --project C123 --workdir repo -- 'npm test'
sudo -n /usr/local/libexec/sandbox-helper process:kill --project C123
```

## Run

Configure the service using [application.conf.sample](src/main/resources/application.conf.sample).

```bash
./gradlew :sandbox-service:run
./gradlew :sandbox-service:run --args="-config=/etc/sidekick/sandbox-service.conf"
```

Or register a user-level systemd service:

```bash
sandbox-service/register-systemd-service.sh
systemctl --user start junior-sidekick-sandbox.service
journalctl --user -u junior-sidekick-sandbox.service -f
```

## Tests

```bash
node --test sandbox-service/helper/sandbox-helper.test.cjs
./gradlew :sandbox-service:test
```
