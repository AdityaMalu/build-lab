# Keyword and User Blocking (Python)

**Scenario.** Before any post goes live it passes through a content gate. Trust & Safety maintains a list of blocked
keywords and phrases; users who keep hitting them get temporarily blocked automatically. The gate sits on the hot
path of every post, so it must be fast and correct under concurrency.

## What to build (package `gate`)
`ContentGate(time)` (`time.now_millis()`), returning a `Decision`: `ACCEPTED`, `REJECTED_KEYWORD` or `BLOCKED_USER`.
Constants `STRIKE_LIMIT = 3`, `STRIKE_WINDOW_MS` (1 hour) and `AUTO_BLOCK_MS` (24 hours) are given.

| Method | Behaviour |
|---|---|
| `add_keyword(phrase)` / `remove_keyword(phrase)` | One or more words, case-insensitive. Blank or no words → `ValueError`. |
| `block_user(user_id, duration_millis)` | Block until `now + duration`; `duration <= 0` means permanent. |
| `unblock_user(user_id)` | Lift any block **and** clear strikes. |
| `is_blocked(user_id) -> bool` | Active while `now < until`. |
| `submit(user_id, text) -> Decision` | See below. Blank user → `ValueError`; `None` text counts as empty. |
| `active_strikes(user_id) -> int` | Strikes inside the current window. |

### Matching
Text and phrases are split into **tokens**: maximal runs of letters/digits (`str.isalnum()`), lowercased. A phrase
matches if its tokens appear **consecutively** in the text's tokens: `"free money"` matches `"Get FREE   money!!"`
and `"free-money"`, but not `"freemoney"` or `"free the money"`.

### Submit flow
1. Blocked user → `BLOCKED_USER` (no strike).
2. A keyword matches → `REJECTED_KEYWORD` and record a strike at `now`. Strikes expire at `t + STRIKE_WINDOW_MS`.
   Reaching `STRIKE_LIMIT` active strikes auto-blocks the user for `AUTO_BLOCK_MS` and clears their strikes.
3. Otherwise → `ACCEPTED`.

### Concurrency
20 threads submitting violating posts for the same user at once: **exactly 3** get `REJECTED_KEYWORD`, the rest
`BLOCKED_USER`. Keywords may change while posts are being checked.
