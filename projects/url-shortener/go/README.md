# URL Shortener Service (Go)

**Scenario.** Marketing wants short links for campaigns: some permanent, some that expire after a promo ends,
and they want click counts. Build the core service (no HTTP layer here, just the domain logic).

## What to build (package `shortener`)

### `base62.go`
- Alphabet: `0-9`, then `A-Z`, then `a-z` (so `0 → "0"`, `61 → "z"`, `62 → "10"`).
- `Encode(n int64) (string, error)`: negative → error wrapping `ErrInvalidInput`.
- `Decode(s string) (int64, error)`: empty string or an invalid character → error wrapping `ErrInvalidInput`.

### `shortener.go`: `NewShortener(clock Clock) *Shortener`

| Method | Behaviour |
|---|---|
| `Shorten(longURL string, ttlMillis int64) (string, error)` | Generate a code. `ttlMillis <= 0` means never expires. |
| `ShortenWithAlias(longURL, alias string, ttlMillis int64) (string, error)` | Use a custom code. |
| `Resolve(code string) (string, bool)` | The long URL and `true` if the code exists and isn't expired. Counts a hit. |
| `Stats(code string) (LinkStats, error)` | `LinkStats{Code, Hits, LastAccessMillis}`. Unknown code → `ErrNotFound`. |

Errors (all package-level `var`s, compare with `errors.Is`): `ErrInvalidURL`, `ErrInvalidAlias`, `ErrAliasTaken`,
`ErrNotFound`, `ErrInvalidInput`.

## Rules
1. **URL validation:** must start with `http://` or `https://`, have something after the scheme, no whitespace,
   max 2048 bytes → otherwise `ErrInvalidURL`.
2. **Generated codes** are Base62, 6–10 characters, and never collide with each other or with aliases.
   A counter-based scheme is fine (offset it so codes are at least 6 chars).
3. **Dedup:** calling `Shorten` with the same URL again returns the **same code**, as long as that earlier generated
   link has not expired. Aliases are never reused by dedup.
4. **Aliases** must match `^[A-Za-z0-9_-]{3,32}$`, else `ErrInvalidAlias`. Already taken (by an alias or a generated
   code, even if expired) → `ErrAliasTaken`.
5. **Expiry:** a link created at `t` with TTL `d` resolves while `now < t + d`. Expired links don't resolve and don't
   count hits.
6. **Stats:** `Hits` counts successful resolves; `LastAccessMillis` is the time of the latest successful resolve,
   or `-1` if never accessed.
7. Safe for concurrent use.
