# Keyword and User Blocking (C++)

**Scenario.** Before any post goes live it passes through a content gate. Trust & Safety maintains a list of blocked
keywords and phrases; users who keep hitting them get temporarily blocked automatically. The gate sits on the hot
path of every post, so it must be fast and correct under concurrency.

## What to build (header `content_gate.hpp`, C++20)
`ContentGate(const Clock&)`, returning a `Decision`: `Accepted`, `RejectedKeyword` or `BlockedUser`.
Constants `kStrikeLimit = 3`, `kStrikeWindowMillis` (1 hour) and `kAutoBlockMillis` (24 hours) are given.

| Method | Behaviour |
|---|---|
| `addKeyword(phrase)` / `removeKeyword(phrase)` | One or more words, case-insensitive. No words → `std::invalid_argument`. |
| `blockUser(userId, durationMillis)` | Block until `now + duration`; `duration <= 0` means permanent. |
| `unblockUser(userId)` | Lift any block **and** clear strikes. |
| `bool isBlocked(userId)` | Active while `now < until`. |
| `Decision submit(userId, text)` | See below. Blank user → `std::invalid_argument`. |
| `int activeStrikes(userId)` | Strikes inside the current window. |

### Matching
Text and phrases are split into **tokens**: maximal runs of ASCII letters/digits (`std::isalnum`), lowercased.
A phrase matches if its tokens appear **consecutively** in the text's tokens: `"free money"` matches
`"Get FREE   money!!"` and `"free-money"`, but not `"freemoney"` or `"free the money"`.

### Submit flow
1. Blocked user → `BlockedUser` (no strike).
2. A keyword matches → `RejectedKeyword` and record a strike at `now`. Strikes expire at `t + kStrikeWindowMillis`.
   Reaching `kStrikeLimit` active strikes auto-blocks the user for `kAutoBlockMillis` and clears their strikes.
3. Otherwise → `Accepted`.

### Concurrency
20 threads submitting violating posts for the same user at once: **exactly 3** get `RejectedKeyword`, the rest
`BlockedUser`. Keywords may change while posts are being checked.
