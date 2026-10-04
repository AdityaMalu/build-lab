# Contributing to Build Lab

Thanks for helping! New projects, better tests, bug fixes and UI improvements are all welcome.

## Ground rules
- **Original material only.** Write specs, code and tests yourself. Don't copy problems from other
  platforms, courses or books.
- **Plain JDK.** No Maven/Gradle dependencies; everything must run with `java server/LabServer.java`.
- **Every reference solution must pass, every starter must compile.** CI enforces both.

## Adding a project
1. Pick an id (lowercase, dashes), e.g. `job-scheduler`, and add an entry to `projects/catalog.json`
   (`title`, `difficulty`, `minutes`, `kind` = `build` or `debug`, `summary`, `tags`).
2. Create:
   ```
   projects/<id>/README.md          the spec: scenario, contract, rules, hints
   projects/<id>/starter/src/...    skeleton (build) or buggy code (debug); must compile
   projects/<id>/solution/src/...   reference solution
   projects/<id>/tests/src/...      *Test.java classes using testkit (@Test, Assert, Concurrent)
   ```
   Use one Java package per project. Starter and solution share the same public API.
3. Tests are part of the spec: give every `@Test` a descriptive name and every assertion a message
   that tells the learner what went wrong.
4. For debug projects, describe the **symptoms** in the README (bug reports), not the bugs.
5. Check it:
   ```
   .\lab.ps1 test <id> solution     # must pass
   .\lab.ps1 test <id> starter      # must compile (tests may fail)
   .\lab.ps1 verify                 # everything still passes
   ```
   On macOS/Linux use `java server/LabServer.java test <id> solution`.

## Sandbox notes
On the hosted site, learner code runs without file access outside a temp folder, without network
access except localhost, and without starting processes. Tests that need temp files should use
`Files.createTempDirectory`; tests that need sockets should bind to `127.0.0.1` on port 0.

## Pull requests
Keep PRs focused, describe what changed and why, and make sure the `verify` workflow is green.
