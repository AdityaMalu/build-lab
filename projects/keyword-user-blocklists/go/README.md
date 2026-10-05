# Keyword and User Blocking (Go)

**Scenario.** Before any post goes live it passes through a content gate. Trust & Safety maintains a list of blocked
keywords and phrases; users who keep hitting them get temporarily blocked automatically. The gate sits on the hot
path of every post, so it must be fast and correct under concurrency.

## What to build (package `gate`)
`NewGate(clock Clock) *Gate`, returning a `Decision`: `Accepted`, `RejectedKeyword` or `BlockedUser`.
Constants `StrikeLimit = 3`, `StrikeWindowMillis` (1 hour) and `AutoBlockMillis` (24 hours) are given.

| Method | Behaviour |
|---|---|
| `AddKeyword(phrase) error` / `RemoveKeyword(phrase) error` | One or more words, case-insensitive. No words → `ErrInvalidArgument`. |
| `BlockUser(userID string, durationMillis int64) error` | Block until `now + duration`; `duration <= 0` means permanent. |
| `UnblockUser(userID string) error` | Lift any block **and** clear strikes. |
| `IsBlocked(userID string) bool` | Active while `now < until`. |
| `Submit(userID, text string) (Decision, error)` | See below. Blank user → `ErrInvalidArgument`. |
| `ActiveStrikes(userID string) int` | Strikes inside the current window. |

### Matching
Text and phrases are split into **tokens**: maximal runs of letters/digits (`unicode.IsLetter`/`IsDigit`),
lowercased. A phrase matches if its tokens appear **consecutively** in the text's tokens: `"free money"` matches
`"Get FREE   money!!"` and `"free-money"`, but not `"freemoney"` or `"free the money"`.

### Submit flow
1. Blocked user → `BlockedUser` (no strike).
2. A keyword matches → `RejectedKeyword` and record a strike at `now`. Strikes expire at `t + StrikeWindowMillis`.
   Reaching `StrikeLimit` active strikes auto-blocks the user for `AutoBlockMillis` and clears their strikes.
3. Otherwise → `Accepted`.

### Concurrency
20 goroutines submitting violating posts for the same user at once: **exactly 3** get `RejectedKeyword`, the rest
`BlockedUser`. Keywords may change while posts are being checked.
