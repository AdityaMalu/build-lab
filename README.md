# Build Lab: backend practice projects in Java, Python, Go and C++

[![verify](https://github.com/AdityaMalu/build-lab/actions/workflows/verify.yml/badge.svg)](https://github.com/AdityaMalu/build-lab/actions/workflows/verify.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

18 hands-on projects (10 build, 8 debug) with specs, starter code, test suites and reference solutions,
plus a web UI to read the spec, edit code (with autocomplete and Ctrl+click go-to-definition), and run individual or all tests. **Pick your language** in the top bar;
your code, results and progress are kept separately for each language. No build tools or libraries needed.

| Language | Projects | Style |
|---|---|---|
| Java | all 18 | exceptions, `synchronized` / `java.util.concurrent`, our mini test kit |
| Python | 7 so far (see the language picker) | `ValueError`/`KeyError`, `threading`, standard library only |
| Go | 7 so far | returned `error` values with `errors.Is`, goroutines + `sync`, standard `testing` |
| C++ (C++20) | 7 so far | header-only, `std::invalid_argument`, `std::thread`/`std::mutex` |

More projects are being ported to Python, Go and C++ in batches.

## Three ways to use it

**1. In your browser, nothing to install:** **https://build-lab.onrender.com**
Your code and progress are saved in your browser (use *Export a backup* on the home page to keep them safe).

**2. Your own cloud machine (GitHub Codespaces):**

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/AdityaMalu/build-lab)

The lab opens automatically on port 8090. Uses your own free Codespaces quota.
Codespaces are deleted after ~30 days without use, so to keep your work long term **fork this repo first**,
open the Codespace from your fork, and commit your `workspace/` there (remove `workspace/` from `.gitignore`).

**3. On your machine:** needs JDK 17+ (runs the server). For the other languages: Python 3.10+, Go 1.22+,
and g++ 11+ (C++20). The language picker only offers what's installed.
```
git clone https://github.com/AdityaMalu/build-lab.git
cd build-lab
.\lab.ps1                      # Windows, opens http://localhost:8090
java server/LabServer.java     # macOS / Linux / any OS
```
Windows installs: `winget install Microsoft.OpenJDK.21 Python.Python.3.12 GoLang.Go BrechtSanders.WinLibs.POSIX.UCRT`

## Command line
```
.\lab.ps1 test rate-limiter                       # your Java code
.\lab.ps1 test rate-limiter workspace go          # your Go code (java | python | go | cpp)
.\lab.ps1 test rate-limiter solution python       # the reference solution
.\lab.ps1 verify                                  # every solution, every language
```
On macOS/Linux: `java server/LabServer.java test rate-limiter workspace go`

## Two ways to run it
| | Local mode (default) | Hosted mode (`--hosted` / `LAB_MODE=hosted`) |
|---|---|---|
| Who | you, on your machine or in a Codespace | anyone with the URL |
| Your code | files in `workspace/<project>/<lang>/src`, editable in any IDE | autosaved in each visitor's browser |
| Progress | `workspace/progress.json` | each visitor's browser (Export/Import backup on the home page) |
| Test runs | normal processes | sandboxed (below); rate limited to 30 runs per 10 minutes per visitor |

**Hosted sandbox.** Submitted code is compiled and run as an unprivileged `runner` user with CPU, process,
file-size and file-descriptor limits, in a fresh temp folder that is deleted afterwards. On top of that, Java runs
under a security manager policy, and Python, Go and C++ install a seccomp filter before any submitted code
executes: no starting programs, no new processes, no network sockets, no signalling or tracing other
processes, no privilege changes. CI submits probe code in every language on each push and fails if anything gets
through (`.github/scripts/sandbox-probes.sh`).

## Deploying the website (Render, free)
1. Sign in at https://render.com and allow it to access the `build-lab` repo.
2. **New → Blueprint →** pick `build-lab`. Render reads `render.yaml` and builds the `Dockerfile`.
3. Share the `https://build-lab-xxxx.onrender.com` URL. Every push to `main` redeploys.

Free instances sleep after ~15 minutes idle; the `keep-awake` workflow pings the site every 10 minutes
to prevent that (edit the URL in `.github/workflows/keep-awake.yml` if you deploy your own copy).

## Layout
```
projects/catalog.json                  project list
projects/<id>/README.md                spec (Java version, also the fallback)
projects/<id>/<lang>/README.md         spec for that language's API
projects/<id>/<lang>/starter/src       starting point (copied to your workspace on first open)
projects/<id>/<lang>/tests/src         tests (read them, they're part of the spec)
projects/<id>/<lang>/solution/src      reference solution
workspace/<id>/<lang>/src              YOUR code: edit here from the UI or in your IDE
workspace/progress.json                solved / attempted per language
testkit/java | python | go | cpp       test harnesses and sandbox code
server/LabServer.java                  server + test runners
web/                                   the UI
```
**Reset** in the UI restores a project's starter code for the current language.

## Projects
| Project | Type | Level | Focus |
|---|---|---|---|
| Rate Limiter | build | Easy | token bucket, sliding window, per-client locking |
| Comments & Replies | build | Easy | tree modeling, soft delete, snapshots |
| URL Shortener | build | Medium | Base62, aliases, expiry, dedup |
| Reservation Service Incident | debug | Medium | half-open intervals, check-then-act races, data repair |
| File Deduplication | build | Medium | size, hash, byte compare pipeline, failure isolation |
| Feature Flags & A/B | build | Medium | FNV-1a bucketing, rollouts, weighted variants |
| Image Transform Pipeline | build | Medium | ordered concurrency, fair semaphore memory budget |
| Loan Lifecycle | debug | Medium | state machine, lost updates, lock ordering |
| Return Risk Assessment | debug | Easy | boundary conditions, batch isolation |
| Comment Moderation | debug | Medium | whole-word matching, atomic transitions, listener isolation |
| Retry-Safe Issue Service | build | Hard | idempotency, optimistic locking, cursors, HTTP |
| Movie Recommendations | debug | Medium | scoring, tie-breaks, shared mutable state |
| Keyword & User Blocking | build | Medium | tokenizing, strike windows, exact counts under races |
| Persistent Private Watchlists | debug | Medium | authorization, atomic file writes, id recovery |
| Password Reset | debug | Easy | config, clocks, timezones, single-use codes |
| TTL + LRU Cache | build | Medium | expiry vs eviction, access order, single-flight loading |
| Retrying Job Queue | build | Hard | leases and tokens, exponential backoff, dead letters |
| Leaderboard Service | debug | Medium | competition ranking, tie order, mutating TreeSet entries, paging |

All specs, code and tests here are original material written for practice.

## Design docs
- [Architecture (HLD + LLD)](docs/ARCHITECTURE.md): components, API, run pipeline, sandbox, CI/CD, trade-offs
- [Scaling plan](docs/SCALING_PLAN.md): job queue + gVisor workers, warm pools, accounts, distributed rate limiting, observability (free tier only)
- [Observability](docs/OBSERVABILITY.md): run logs, `/metrics`, Grafana Cloud setup and dashboard

## Contributing
New projects, ports to other languages and fixes are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
[MIT](LICENSE)
