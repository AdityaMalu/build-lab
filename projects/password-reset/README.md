# Password Reset

**Scenario.** Security review of the password-reset flow turned up a list of problems: the configured code lifetime
is ignored, some codes come out with only 5 digits, expiry times shown to users are in the server's timezone, reset
codes can be reused, and passwords are stored in plain text. Fix the service so it meets the spec.

## Pieces (package `reset`)
- `UserStore` / `InMemoryUserStore`: given.
- `CodeSource`: injected randomness (`nextInt(bound)`); `CodeSource.secure()` wraps `SecureRandom`. Given.
- `PasswordHasher.hash(password)`: given. Always store `PasswordHasher.hash(...)`, never the raw password.
- `PasswordResetService(Properties config, Clock clock, UserStore users, CodeSource codes)`: **this is what you fix.**

## Configuration
| Key | Default | Meaning |
|---|---|---|
| `reset.ttl.minutes` | `15` | Code lifetime |
| `reset.code.length` | `6` | Digits in a code, must be 4–10 (`IllegalArgumentException` in the constructor otherwise) |
| `reset.timezone` | `UTC` | Zone used by `describeExpiry` |

Non-numeric values or an invalid zone → `IllegalArgumentException` in the constructor.

## Behaviour
| Method | Contract |
|---|---|
| `String requestReset(String email)` | Unknown email → `NoSuchElementException`. Generates a code of **exactly** `length` digits (leading zeros allowed) from `codes.nextInt(10^length)`, zero-padded. Replaces any earlier pending code for that email. Expires at `clock.instant() + ttl`. |
| `Instant expiresAt(String email)` | Expiry of the pending code; none pending → `NoSuchElementException`. |
| `String describeExpiry(String email)` | Expiry formatted as `yyyy-MM-dd HH:mm z` (with `Locale.ENGLISH`) in the **configured** zone, e.g. `2026-03-01 15:45 IST`. |
| `boolean confirmReset(String email, String code, String newPassword)` | New password shorter than 8 chars → `IllegalArgumentException` (and the code is **not** consumed). Returns `false` if there's no pending code, the code is wrong, or it has expired (expired **at** `now >= expiresAt`). On success, stores the hash and **consumes** the code, so a second use returns `false`. **After 5 wrong guesses** the pending code is invalidated, even if the 6th guess is right. |

All time comes from the injected `Clock`; never call `Instant.now()` or `LocalDateTime.now()` without it.
