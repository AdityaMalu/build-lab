#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu 24.04 VM (x86-64 or arm64) for MachineCodingLab: Docker, gVisor, Java,
# the build-lab image, the worker service and the Caddy + API + Redis stack. Safe to re-run.
#
#   git clone https://github.com/AdityaMalu/machine-coding-lab.git && cd machine-coding-lab
#   sudo LAB_DOMAIN=129-146-1-2.sslip.io bash deploy/oracle/setup.sh
#
# Secrets are generated into deploy/oracle/.env (mode 600, git-ignored) and never leave the VM.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run with sudo." >&2; exit 1; }
REPO_DIR=$(cd "$(dirname "$0")/../.." && pwd)
DEPLOY="$REPO_DIR/deploy/oracle"
ENV_FILE="$DEPLOY/.env"
if [ ! -f "$ENV_FILE" ]; then : "${LAB_DOMAIN:?Set LAB_DOMAIN, e.g. sudo LAB_DOMAIN=129-146-1-2.sslip.io bash $0}"; fi

echo "== packages: Docker, Compose, Java 21 (for the worker)"
apt-get update
apt-get install -y ca-certificates curl gnupg openssl docker.io docker-compose-v2 openjdk-21-jdk-headless

echo "== gVisor (https://gvisor.dev/docs/user_guide/install/)"
if ! command -v runsc >/dev/null; then
  curl -fsSL https://gvisor.dev/archive.key | gpg --dearmor --yes -o /usr/share/keyrings/gvisor-archive-keyring.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/gvisor-archive-keyring.gpg] https://storage.googleapis.com/gvisor/releases release main" \
    > /etc/apt/sources.list.d/gvisor.list
  apt-get update
  apt-get install -y runsc
fi
runsc install          # registers the "runsc" runtime in /etc/docker/daemon.json (systrap platform: no KVM needed)
systemctl restart docker

echo "== secrets"
if [ ! -f "$ENV_FILE" ]; then
  pw=$(openssl rand -hex 24)
  umask 077
  cat > "$ENV_FILE" <<EOF
LAB_DOMAIN=$LAB_DOMAIN
REDIS_PASSWORD=$pw
LAB_REDIS_URL=redis://:$pw@127.0.0.1:6379
LAB_WORKER_SLOTS=${LAB_WORKER_SLOTS:-2}
LAB_METRICS_TOKEN=$(openssl rand -hex 24)
# Optional, see docs/OBSERVABILITY.md:
# LAB_LOKI_URL=
# LAB_LOKI_USER=
# LAB_LOKI_TOKEN=
EOF
  echo "wrote $ENV_FILE"
fi

echo "== image and worker"
docker build -t build-lab "$REPO_DIR"
mkdir -p "$REPO_DIR/build/classes" /var/lib/buildlab/jobs
javac -d "$REPO_DIR/build/classes" "$REPO_DIR/server/LabServer.java"
echo -n "gVisor check: "
docker run --rm --runtime=runsc --network=none --entrypoint dmesg build-lab | head -n 1   # prints "Starting gVisor..."
sed "s#REPO_DIR#$REPO_DIR#g" "$DEPLOY/buildlab-worker.service" > /etc/systemd/system/buildlab-worker.service
systemctl daemon-reload
systemctl enable buildlab-worker
systemctl restart buildlab-worker

echo "== firewall: allow HTTP and HTTPS (Oracle's Ubuntu images only allow SSH by default)"
if command -v iptables >/dev/null && ! iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null; then
  iptables -I INPUT -p tcp --dport 80 -j ACCEPT
  iptables -I INPUT -p tcp --dport 443 -j ACCEPT
  if command -v netfilter-persistent >/dev/null; then netfilter-persistent save; fi
fi

echo "== website: Caddy + API + Redis"
docker compose --env-file "$ENV_FILE" -f "$DEPLOY/docker-compose.yml" up -d

domain=$(grep '^LAB_DOMAIN=' "$ENV_FILE" | cut -d= -f2)
echo
echo "Done. Open https://$domain (the first HTTPS certificate can take a minute)."
echo "Also allow TCP 80 and 443 in the VM's cloud firewall (Oracle: VCN security list ingress rules)."
echo "Worker log: journalctl -u buildlab-worker -f     Site log: docker compose -f $DEPLOY/docker-compose.yml logs -f api"
