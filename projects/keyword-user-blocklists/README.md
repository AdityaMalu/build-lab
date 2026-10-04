# Keyword and User Blocking

**Scenario.** Before any post goes live it passes through a content gate. Trust & Safety maintains a list of blocked
keywords and phrases; users who keep hitting them get temporarily blocked automatically. The gate sits on the hot path
of every post, so it must be fast and correct under concurrency.

## What to build (package `gate`)
`ContentGate(TimeSource time)` returning a `Decision`: `ACCEPTED`, `REJECTED_KEYWORD`, or `BLOCKED_USER`.

| Method | Behaviour |
|---|---|
| `void addKeyword(String phrase)` / `void removeKeyword(String phrase)` | A phrase is one or more words. Blank → `IllegalArgumentException`. Case-insensitive. |
| `void blockUser(String userId, long durationMillis)` | Block until `now + duration`. `duration <= 0` means permanent. |
| `void unblockUser(String userId)` | Lift any block **and** clear strikes. |
| `boolean isBlocked(String userId)` | Block is active while `now < until`. |
| `Decision submit(String userId, String text)` | See below. Blank user → `IllegalArgumentException`; null text is treated as empty. |
| `int activeStrikes(String userId)` | Strikes inside the current window. |

### Matching rules
- Text and phrases are split into **tokens**: maximal runs of letters/digits, lowercased. Everything else is a separator.
- A phrase matches if its tokens appear **consecutively** in the text's tokens.
  `"free money"` matches `"Get FREE   money!!"` and `"free-money"`, but not `"freemoney"` or `"free the money"`.

### Submit flow
1. If the user is blocked → `BLOCKED_USER` (no strike is recorded).
2. If any keyword matches → `REJECTED_KEYWORD` and record a **strike** at `now`.
   Strikes older than `STRIKE_WINDOW_MS` (1 hour; a strike at `t` expires at `t + window`) don't count.
   When the user reaches `STRIKE_LIMIT` (3) active strikes, they're auto-blocked for `AUTO_BLOCK_MS` (24 hours) and
   their strikes are cleared.
3. Otherwise → `ACCEPTED`.

### Concurrency
If 20 threads submit violating posts for the same user at the same time, **exactly 3** get `REJECTED_KEYWORD` and the
rest get `BLOCKED_USER`. Keyword list updates may happen while posts are being checked.
