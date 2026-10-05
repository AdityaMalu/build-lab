# Password Reset (C++)

**Scenario.** Security review of the password-reset flow turned up a list of problems: the configured code lifetime
is ignored, some codes come out with only 5 digits, expiry times shown to users ignore the configured timezone offset,
reset codes can be reused, wrong guesses are unlimited, and passwords are stored in plain text. Fix the service.

## Pieces (header `password_reset.hpp`, C++20)
- `UserStore`, `InMemoryUserStore` and `hashPassword(password)`: given in `reset_support.hpp`. Always store
  `hashPassword(...)`, never the raw password. (The demo hash is FNV-1a; production code would use a slow, salted
  KDF such as bcrypt or Argon2.)
- `codes`: a `std::function<long long(long long bound)>` returning a uniform value in `[0, bound)`.
- `Clock`: `int64_t nowMillis() const`.
- `PasswordResetService(std::map<std::string, std::string> config, const Clock&, UserStore&, codes)`:
  **this is what you fix.**

## Configuration
| Key | Default | Meaning |
|---|---|---|
| `reset.ttl.minutes` | `15` | Code lifetime, must be > 0 |
| `reset.code.length` | `6` | Digits in a code, 4–10 |
| `reset.utc.offset.minutes` | `0` | Offset used by `describeExpiry`, −720…840 (e.g. `330` = UTC+05:30) |

Non-numeric or out-of-range values → `std::invalid_argument` from the constructor.

## Behaviour
| Method | Contract |
|---|---|
| `std::string requestReset(email)` | Unknown email → `std::out_of_range`. A code of **exactly** `length` digits (leading zeros allowed) from `codes(10^length)`, zero-padded. Replaces any earlier pending code. Expires at `clock.nowMillis() + ttl`. |
| `int64_t expiresAt(email)` | Expiry (epoch millis) of the pending code; none pending → `std::out_of_range`. |
| `std::string describeExpiry(email)` | Expiry as `YYYY-MM-DD HH:MM` in the configured offset, followed by ` UTC` (offset 0) or ` UTC+05:30` / ` UTC-01:30`. |
| `bool confirmReset(email, code, newPassword)` | Password shorter than 8 chars → `std::invalid_argument` (code **not** consumed). `false` if nothing is pending, the code is wrong, or it has expired (expired **at** `now >= expiresAt`). On success: store the hash and **consume** the code. **After 5 wrong guesses** the pending code is invalidated. |

All time comes from the injected `Clock`; never call `std::chrono::system_clock::now()`. Two racing confirmations
with the same code can't both succeed.
