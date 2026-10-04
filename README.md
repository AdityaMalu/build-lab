# Build Lab: Java backend practice projects

[![verify](https://github.com/AdityaMalu/build-lab/actions/workflows/verify.yml/badge.svg)](https://github.com/AdityaMalu/build-lab/actions/workflows/verify.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

18 hands-on projects (10 build, 8 debug) with specs, starter code, test suites and reference solutions,
plus a web UI to read the spec, edit code, and run individual or all tests. Pure JDK, no Maven, no libraries.

## Three ways to use it

**1. In your browser, nothing to install:** **https://build-lab.onrender.com**
Your code and progress are saved in your browser (use *Export a backup* on the home page to keep them safe).

**2. Your own cloud machine (GitHub Codespaces):**

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/AdityaMalu/build-lab)

Java is preinstalled and the lab opens automatically on port 8090. Uses your own free Codespaces quota.
Codespaces are deleted after ~30 days without use, so to keep your work long term **fork this repo first**,
open the Codespace from your fork, and commit your `workspace/` there (remove `workspace/` from `.gitignore`).

**3. On your machine:** needs JDK 17+ (with `javac`)
```
git clone https://github.com/AdityaMalu/build-lab.git
cd build-lab
.\lab.ps1                      # Windows, opens http://localhost:8090
java server/LabServer.java     # macOS / Linux / any OS
```
Windows JDK install: `winget install Microsoft.OpenJDK.21`

## Command line
```
.\lab.ps1 test rate-limiter             # your code
.\lab.ps1 test rate-limiter solution    # the reference solution
.\lab.ps1 verify                        # every solution against its tests
```

## Two ways to run it
| | Local mode (default) | Hosted mode (`--hosted` / `LAB_MODE=hosted`) |
|---|---|---|
| Who | you, on your machine or in a Codespace | anyone with the URL |
| Your code | files in `workspace/`, editable in any IDE | autosaved in each visitor's browser |
| Progress | `workspace/progress.json` | each visitor's browser (Export/Import backup on the home page) |
| Test runs | normal JVM | sandboxed JVM: no file access outside a temp dir, no internet, no processes; rate limited |

## Deploying the website (Render, free)
1. Sign in at https://render.com with GitHub and allow it to access the `build-lab` repo.
2. **New → Blueprint →** pick `build-lab`. Render reads `render.yaml` and builds the `Dockerfile`.
3. Share the `https://build-lab-xxxx.onrender.com` URL. Every push to `main` redeploys.

Free instances sleep after ~15 minutes idle; the `keep-awake` workflow pings the site every 10 minutes
to prevent that (edit the URL in `.github/workflows/keep-awake.yml` if you deploy your own copy).
Visitors' work isn't affected by restarts because it lives in their browsers.

## Codespaces
On GitHub: **Code → Codespaces → Create codespace**. Java 21 is preinstalled and the lab starts
automatically on port 8090 in local mode, with your workspace persisted in the codespace.

## Layout
```
projects/<id>/README.md        spec
projects/<id>/starter/src      starting point (copied to workspace on first open)
projects/<id>/tests/src        tests (read them, they're part of the spec)
projects/<id>/solution/src     reference solution
workspace/<id>/src             YOUR code: edit here from the UI or open it in IntelliJ/VS Code
workspace/progress.json        solved / attempted status
testkit/src                    tiny test framework (@Test, assertions, concurrency helper)
server/LabServer.java          local server + test runner
web/                           the UI
```
**Reset** in the UI restores a project's starter code. Deleting `workspace/` resets everything.

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

## Contributing
New projects and fixes are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
[MIT](LICENSE)
