#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

IMAGE_NAME="${IMAGE_NAME:-sidekick-bash-rootfs}"
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/data/sandbox/bash-rootfs}"

docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"

container="$(docker create "$IMAGE_NAME")"
cleanup() {
    docker rm "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
docker export "$container" | tar -C "$OUTPUT_DIR" -xf -

if [[ -f /etc/resolv.conf ]]; then
    cp /etc/resolv.conf "$OUTPUT_DIR/etc/resolv.conf"
    chmod 0644 "$OUTPUT_DIR/etc/resolv.conf"
fi

printf 'Exported bash sandbox rootfs to %s\n' "$OUTPUT_DIR"
