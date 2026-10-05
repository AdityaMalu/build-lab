#!/usr/bin/env bash
# Deploys the latest main: pull, rebuild the image and the worker, restart both.
#   sudo bash deploy/oracle/update.sh
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run with sudo." >&2; exit 1; }
REPO_DIR=$(cd "$(dirname "$0")/../.." && pwd)
DEPLOY="$REPO_DIR/deploy/oracle"
git -C "$REPO_DIR" pull --ff-only
docker build -t build-lab "$REPO_DIR"
javac -d "$REPO_DIR/build/classes" "$REPO_DIR/server/LabServer.java"
systemctl restart buildlab-worker
docker compose --env-file "$DEPLOY/.env" -f "$DEPLOY/docker-compose.yml" up -d --force-recreate api
echo "Updated to $(git -C "$REPO_DIR" log --oneline -1)"
