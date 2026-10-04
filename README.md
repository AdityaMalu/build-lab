# Build Lab: Java backend practice projects

15 hands-on projects (8 build, 7 debug) with specs, starter code, test suites and reference solutions,
plus a local web UI to read the spec, edit code, and run tests. Pure JDK, no Maven, no libraries.

## Requirements
JDK 17 or newer (needs `javac`, not just a JRE):
```
winget install Microsoft.OpenJDK.21
```

## Start
```
cd D:\amazon_prep\practice-lab
.\lab.ps1                 # opens http://localhost:8090
```
Or without the script: `java server/LabServer.java`

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

Free instances sleep after ~15 minutes idle; the first visit after that takes ~1 minute to wake up.
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

All specs, code and tests here are original material written for practice.
