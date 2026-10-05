# Password Reset (Go)

**Scenario.** Security review of the password-reset flow turned up a list of problems: the configured code lifetime
is ignored, some codes come out with only 5 digits, expiry times shown to users ignore the configured timezone offset,
reset codes can be reused, wrong guesses are unlimited, and passwords are stored in plain text. Fix the service.

## Pieces (package `reset`)
- `UserStore`, `NewMemoryStore()` and `HashPassword(password)`: given. Always store `HashPassword(...)`.
- `codes func(bound int64) int64`: a uniform value in `[0, bound)` (tests inject fixed values).
- `Clock`: `NowMillis() int64`.
- `NewService(cfg map[string]string, clock Clock, users UserStore, codes func(int64) int64) (*Service, error)`:
  **this is what you fix.**

## Configuration
| Key | Default | Meaning |
|---|---|---|
| `reset.ttl.minutes` | `15` | Code lifetime, must be > 0 |
| `reset.code.length` | `6` | Digits in a code, 4–10 |
| `reset.utc.offset.minutes` | `0` | Offset used by `DescribeExpiry`, −720…840 (e.g. `330` = UTC+05:30) |

Non-numeric or out-of-range values → `NewService` returns an error wrapping `ErrInvalidConfig`.

## Behaviour
| Method | Contract |
|---|---|
| `RequestReset(email) (string, error)` | Unknown email → `ErrUnknownUser`. A code of **exactly** `length` digits (leading zeros allowed) from `codes(10^length)`, zero-padded. Replaces any earlier pending code. Expires at `clock.NowMillis() + ttl`. |
| `ExpiresAt(email) (int64, error)` | Expiry (epoch millis) of the pending code; none pending → `ErrNoPendingReset`. |
| `DescribeExpiry(email) (string, error)` | Expiry as `2006-01-02 15:04` in the configured offset, followed by ` UTC` (offset 0) or ` UTC+05:30` / ` UTC-01:30`. |
| `ConfirmReset(email, code, newPassword) (bool, error)` | Password shorter than 8 chars → `ErrWeakPassword` (code **not** consumed). `false, nil` if nothing is pending, the code is wrong, or it has expired (expired **at** `now >= expiresAt`). On success: store the hash and **consume** the code (a second use returns `false`). **After 5 wrong guesses** the pending code is invalidated. |

All time comes from the injected `Clock`; never call `time.Now()`. Two racing confirmations with the same code can't
both succeed.
