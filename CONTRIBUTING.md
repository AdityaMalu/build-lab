# Contributing to MachineCodingLab

Thanks for helping! New projects, ports to other languages, better tests, bug fixes and UI improvements are all
welcome.

## Ground rules
- **Original material only.** Write specs, code and tests yourself. Don't copy problems from other
  platforms, courses or books.
- **No dependencies.** Standard library only in every language; everything must run through
  `java server/LabServer.java`.
- **Every reference solution must pass, every starter must compile.** CI enforces both, for every language.

## Layout of a project
```
projects/<id>/README.md                     scenario + Java contract (also the fallback spec)
projects/<id>/<lang>/README.md              same scenario, that language's API and error conventions
projects/<id>/<lang>/starter/src/...        skeleton (build) or buggy code (debug)
projects/<id>/<lang>/solution/src/...       reference solution with the same public API
projects/<id>/<lang>/tests/src/...          tests
```
Register new projects in `projects/catalog.json` (`title`, `difficulty`, `minutes`, `kind` = `build` or `debug`,
`summary`, `tags`). Languages are detected from the folders that exist.

## Per-language conventions
| | Code location | Tests | Test names |
|---|---|---|---|
| Java | one package, e.g. `src/ratelimiter/*.java` | `tests/src/<pkg>/*Test.java` with `testkit.Test`/`Assert` | `@Test("readable name")` |
| Python | one package, e.g. `src/ratelimiter/__init__.py` | `tests/src/*_test.py`, classes ending in `Test`, `from labtest import ...` | `@test("readable name")` |
| Go | one package folder, e.g. `src/ratelimiter/*.go` | `tests/src/<pkg>/*_test.go`, same package, standard `testing` | a `// Test: readable name` comment above each `func TestX(t *testing.T)` |
| C++ | headers in `src/` (`.cpp` files in `src/` are compiled too) | `tests/src/*_test.cpp`, `#include "labtest.hpp"` | `LAB_TEST(Suite, method, "readable name")` |

- Errors should feel native: exceptions in Java/Python/C++, wrapped sentinel errors (`errors.Is`) in Go.
- Inject time (a clock interface/object); never read the real clock in code under test.
- Give every assertion a message that tells the learner what went wrong.
- For debug projects, the README describes **symptoms** (bug reports), not the bugs, and the starter keeps the same
  planted bugs in every language where the language allows it.
- Hosted runs are sandboxed: tests may use temp files (`Files.createTempDirectory`, `tempfile`, `os.CreateTemp`,
  `$TMPDIR`) but no network except Java's localhost, and never start processes. Go code may not import
  `unsafe`, `os/exec` or use cgo.

## Check your work
```
.\lab.ps1 test <id> solution <lang>     # must pass
.\lab.ps1 test <id> starter <lang>      # must compile (tests may fail)
.\lab.ps1 verify                        # everything still passes
```
On macOS/Linux use `java server/LabServer.java test <id> solution <lang>`.

## Pull requests
Keep PRs focused, describe what changed and why, and make sure the `verify` workflow is green.
