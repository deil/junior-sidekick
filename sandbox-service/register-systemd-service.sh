#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SERVICE_NAME="${SERVICE_NAME:-junior-sidekick-sandbox}"
SERVICE_FILE="$HOME/.config/systemd/user/$SERVICE_NAME.service"
CONFIG_FILE="${CONFIG_FILE:-$SCRIPT_DIR/src/main/resources/application.conf}"

mkdir -p "$(dirname "$SERVICE_FILE")"

cat >"$SERVICE_FILE" <<SERVICE
[Unit]
Description=Junior Sidekick sandbox service
After=network.target

[Service]
Type=simple
WorkingDirectory=$REPO_ROOT
ExecStart=$REPO_ROOT/gradlew :sandbox-service:run --args=-config=$CONFIG_FILE
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
SERVICE

systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME.service"

printf 'Registered %s\n' "$SERVICE_FILE"
printf 'Start it with: systemctl --user start %s.service\n' "$SERVICE_NAME"
printf 'View logs with: journalctl --user -u %s.service -f\n' "$SERVICE_NAME"
