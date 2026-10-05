# Password Reset (Python)

**Scenario.** Security review of the password-reset flow turned up a list of problems: the configured code lifetime
is ignored, some codes come out with only 5 digits, expiry times shown to users ignore the configured timezone offset,
reset codes can be reused, wrong guesses are unlimited, and passwords are stored in plain text. Fix the service.

## Pieces (package `reset`)
- `InMemoryUserStore` and `hash_password(password)`: given. Always store `hash_password(...)`, never the raw password.
- `codes`: a function `codes(bound) -> int` returning a uniform value in `[0, bound)` (tests inject fixed values).
- `clock`: an object with `now_millis()`.
- `PasswordResetService(config, clock, users, codes)`: **this is what you fix.**

## Configuration (`config` is a dict of strings)
| Key | Default | Meaning |
|---|---|---|
| `reset.ttl.minutes` | `15` | Code lifetime, must be > 0 |
| `reset.code.length` | `6` | Digits in a code, 4–10 |
| `reset.utc.offset.minutes` | `0` | Offset used by `describe_expiry`, −720…840 (e.g. `330` = UTC+05:30) |

Non-numeric or out-of-range values → `ValueError` in the constructor.

## Behaviour
| Method | Contract |
|---|---|
| `request_reset(email) -> str` | Unknown email → `KeyError`. A code of **exactly** `length` digits (leading zeros allowed) from `codes(10 ** length)`, zero-padded. Replaces any earlier pending code. Expires at `clock.now_millis() + ttl`. |
| `expires_at(email) -> int` | Expiry (epoch millis) of the pending code; none pending → `KeyError`. |
| `describe_expiry(email) -> str` | Expiry as `YYYY-MM-DD HH:MM` in the configured offset, followed by ` UTC` (offset 0) or ` UTC+05:30` / ` UTC-01:30`. |
| `confirm_reset(email, code, new_password) -> bool` | Password shorter than 8 chars → `ValueError` (code **not** consumed). `False` if nothing is pending, the code is wrong, or it has expired (expired **at** `now >= expires_at`). On success: store the hash and **consume** the code (a second use returns `False`). **After 5 wrong guesses** the pending code is invalidated. |

All time comes from the injected `clock`; never call `time.time()` or `datetime.now()`. Must be thread-safe: two
racing confirmations with the same code can't both succeed.
