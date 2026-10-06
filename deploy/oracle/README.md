# Deploying MachineCodingLab on an Oracle Cloud Always Free VM

Phase 2 of the [scaling plan](../../docs/SCALING_PLAN.md): one VM runs everything.

```
browser ─HTTPS─> Caddy ─> API (LAB_ROLE=api) ─XADD─> Redis stream "lab:jobs"
                                   ^                       │ XREADGROUP
                                   └──── BLPOP result ──── worker (systemd, LAB_ROLE=worker)
                                                            │ docker run --runtime=runsc --network=none
                                                            └─> one fresh gVisor container per test run
```

Each run is isolated by:
1. **gVisor** (a user-space kernel) with no network, 1 CPU, 1 GB of memory and 512 processes, deleted
   after the run.
2. **Inside gVisor, the same layers as before:** the unprivileged `runner` user, `prlimit` resource limits,
   the seccomp filter (arm64 and x86-64) or Java's security manager, and the Go source checks.

## 1. Create the VM (Oracle console, done by you)
1. Sign up at https://www.oracle.com/cloud/free/. A card is needed for verification; Always Free resources
   are not charged. Pick a home region close to your users; it can't be changed later.
2. **Compute → Instances → Create instance:**
   - Image: **Canonical Ubuntu 24.04** (aarch64).
   - Shape: **Ampere VM.Standard.A1.Flex**, 2 OCPU and 12 GB, which is the full Always Free allowance.
     If you get "out of host capacity", retry later or try another availability domain.
   - Add your SSH public key, and note the **public IP**.
3. **Networking → your VCN → Security list → Add ingress rules:** TCP **80** and **443** from `0.0.0.0/0`.

## 2. Install (on the VM)
```bash
ssh ubuntu@<public-ip>
git clone https://github.com/AdityaMalu/machine-coding-lab.git && cd machine-coding-lab
sudo LAB_DOMAIN=<public-ip-with-dashes>.sslip.io bash deploy/oracle/setup.sh
```
- The domain can be your own, with an A record pointing at the IP. Without one, `sslip.io` resolves
  `129-146-1-2.sslip.io` to `129.146.1.2`, and Caddy gets a real certificate for it.
- `setup.sh` takes about 10 minutes. It installs Docker, gVisor and Java 21, generates secrets into
  `deploy/oracle/.env` (git-ignored, mode 600), builds the image, starts the worker service and the
  Caddy + API + Redis stack, and opens ports 80/443 in the VM's own firewall.

Check it:
```bash
curl https://<domain>/api/config
journalctl -u buildlab-worker -n 20       # "MachineCodingLab worker ...: 2 slots, runner docker (... runtime runsc)"
curl -H "Authorization: Bearer $(sudo grep LAB_METRICS_TOKEN deploy/oracle/.env | cut -d= -f2)" https://<domain>/metrics | grep -E "workers_online|queue_depth"
```

## 3. Update
```bash
cd machine-coding-lab && sudo bash deploy/oracle/update.sh
```

## Settings (`deploy/oracle/.env`)
| Variable | Default | Meaning |
|---|---|---|
| `LAB_WORKER_SLOTS` | 2 | runs at the same time. Each container may use 1 CPU and 1 GB; 2 slots fit 2 OCPU / 12 GB with room for the API and Redis |
| `LAB_MAX_QUEUE` | 20 | queued runs beyond this get "server busy" (503) |
| `LAB_METRICS_TOKEN` | generated | protects `/metrics` (docs/OBSERVABILITY.md) |
| `LAB_LOKI_*` | unset | optional log shipping to Grafana Cloud |

After editing, run `sudo systemctl restart buildlab-worker` and
`sudo docker compose --env-file deploy/oracle/.env -f deploy/oracle/docker-compose.yml up -d`.

## Notes
- **Keep Render as a fallback.** Render runs the same image in single-process mode (`LAB_ROLE` unset).
  Point the keep-awake workflow (`.github/workflows/keep-awake.yml`) at whichever URL you share.
- **Oracle may reclaim idle Always Free VMs.** Steady traffic helps: point the Grafana scrape (docs/OBSERVABILITY.md) at this VM too.
- **Scaling.** On one VM, "autoscaling" means the worker's slot count. With more machines, run more
  workers against the same Redis: they share the consumer group, so nothing else changes.
- **Tested on every push.** CI (`fleet` job in `.github/workflows/verify.yml`) builds this setup on an
  arm64 runner with gVisor. It runs every language's solution through the queue, checks that runs see
  gVisor's kernel rather than the host's, and runs the sandbox escape probes.
