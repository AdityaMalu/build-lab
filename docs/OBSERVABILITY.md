# Observability

Phase 1 of the [scaling plan](SCALING_PLAN.md). Every test run produces:

1. **A structured log line**, written to stdout always and pushed to Grafana Loki when configured:
   ```json
   {"event":"run","ts":"2026-10-05T16:06:20.790Z","run_id":"d8ff312fa9b57690","site":"hosted",
    "lang":"go","project":"rate-limiter","target":"workspace","selected":0,"outcome":"pass",
    "queue_ms":0,"compile_ms":13059,"exec_ms":511,"total_ms":13570,"tests_passed":12,"tests_total":12}
   ```
   - `outcome` is one of `pass`, `fail`, `compile_error`, `timeout`, `rate_limited`, `busy`, `rejected` or
     `server_error`.
   - `target` is `workspace`, `solution` or `starter`.
   - `selected` is the number of chosen tests; 0 means all.
   - Source code and client IP addresses are never logged.
2. **Prometheus metrics** on `GET /metrics`:

   | Metric | Type | Labels |
   |---|---|---|
   | `lab_runs_total` | counter | `lang`, `outcome` |
   | `lab_run_seconds` | histogram (0.1 s … 240 s) | `lang`, `phase` = `queue` \| `compile` \| `exec` \| `total` |
   | `lab_runs_active`, `lab_runs_waiting`, `lab_run_slots` | gauge | |
   | `lab_jvm_heap_used_bytes`, `lab_jvm_heap_max_bytes`, `lab_start_time_seconds` | gauge | |
   | `lab_log_lines_dropped_total` | counter | |
   | `lab_queue_depth`, `lab_workers_online` | gauge, API role only | runs in the Redis queue; workers seen in the last 30 s |

   Labels are deliberately low-cardinality: about 250 series in total, far under Grafana Cloud's free 10k.
   The project id is only in the logs.
3. **A `timing` object in the run response**, which the UI shows as e.g. `13.6s (compile 13.1s, tests 0.5s)`.

Phases: `queue` is the wait for a free run slot (hosted mode), `compile` covers writing files and compiling
(Python compiles on import, so it is near zero), `exec` is running the tests, `total` is the whole request.

## Settings (environment variables)

| Variable | Purpose |
|---|---|
| `LAB_METRICS_TOKEN` | If set, `/metrics` requires `Authorization: Bearer <token>` or Basic auth with the token as the password. **Set it on any public deployment.** |
| `LAB_LOKI_URL` | Loki push URL, e.g. `https://logs-prod-012.grafana.net/loki/api/v1/push`. Unset = stdout only. |
| `LAB_LOKI_USER` | Grafana Cloud Logs user id (a number). |
| `LAB_LOKI_TOKEN` | Grafana Cloud access policy token with the `logs:write` scope. |

Logs are pushed in batches every 10 s from a bounded in-memory queue. If Loki is unreachable, lines are
dropped (counted in `lab_log_lines_dropped_total`) rather than slowing down test runs.

## Setting up Grafana Cloud (free)

1. Create a free account at https://grafana.com and a stack.
2. **Metrics.**
   1. Generate a long random token, e.g. `openssl rand -hex 32`.
   2. In Render, open the service, go to **Environment**, and set `LAB_METRICS_TOKEN` to that token.
   3. In Grafana, go to **Connections → Add new connection → Metrics Endpoint**, then:
      - URL: `https://<your-app>.onrender.com/metrics`
      - scrape interval: 60 s
      - authentication: Basic, with any username and the token as the password
3. **Logs.**
   1. In the Grafana Cloud portal, open your stack's **Loki** details to find the URL and user id.
   2. Create an access policy token with scope `logs:write`.
   3. Set `LAB_LOKI_URL` (the URL ending in `/loki/api/v1/push`), `LAB_LOKI_USER` and `LAB_LOKI_TOKEN` in Render.
   4. Save, and Render redeploys.
4. **Dashboard.** Go to **Dashboards → New → Import**, upload `observability/grafana-dashboard.json`, and
   pick the Prometheus and Loki data sources from your stack.
5. **Alerts.** Go to **Alerting → Alert rules → New**:
   - **Slow runs:**
     `histogram_quantile(0.95, sum by (le, lang) (rate(lab_run_seconds_bucket{phase="total"}[1h]))) > 60`
     for 15 min.
   - **Broken runs** (the server, not the user's code):
     `sum(increase(lab_runs_total{outcome=~"timeout|server_error"}[1h])) / sum(increase(lab_runs_total[1h])) > 0.5`
     for 15 min.
   - **Memory pressure:** `lab_jvm_heap_used_bytes / lab_jvm_heap_max_bytes > 0.9` for 10 min.

Metrics and logs are kept for 14 days on the free plan.

## Useful log queries (LogQL)

```
# every run of one project
{app="build-lab"} | json | project="job-queue"

# compile time per language, p95 over the last day
quantile_over_time(0.95, {app="build-lab"} | json | outcome=~"pass|fail" | unwrap compile_ms [1d]) by (lang)

# which projects time out
sum by (project, lang) (count_over_time({app="build-lab"} | json | outcome="timeout" [7d]))
```

## Locally

`/metrics` is open when `LAB_METRICS_TOKEN` is unset: `curl localhost:8090/metrics`.
Run log lines appear in the terminal running the server.
