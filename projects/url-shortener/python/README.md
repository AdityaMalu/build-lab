# URL Shortener Service (Python)

**Scenario.** Marketing wants short links for campaigns: some permanent, some that expire after a promo ends,
and they want click counts. Build the core service (no HTTP layer here, just the domain logic).

## What to build (package `shortener`)

### `base62.py`
- Alphabet: `0-9`, then `A-Z`, then `a-z` (so `0 → "0"`, `61 → "z"`, `62 → "10"`).
- `encode(n: int) -> str` for `n >= 0` (negative → `ValueError`).
- `decode(s: str) -> int` (empty, `None`, or an invalid character → `ValueError`).

### `service.py`: `UrlShortener(time)`
`time` is any object with `now_millis() -> int`.

| Method | Behaviour |
|---|---|
| `shorten(long_url, ttl_millis) -> str` | Generate a code. `ttl_millis <= 0` means never expires. |
| `shorten_with_alias(long_url, alias, ttl_millis) -> str` | Use a custom code. |
| `resolve(code) -> str \| None` | Long URL if the code exists and is not expired. Counts a hit. |
| `stats(code) -> LinkStats` | `LinkStats(code, hits, last_access_millis)`. Unknown code → `KeyError`. |

## Rules
1. **URL validation:** must start with `http://` or `https://`, have something after the scheme, no whitespace,
   max 2048 chars → otherwise `ValueError`.
2. **Generated codes** are Base62, 6–10 characters, and never collide with each other or with aliases.
   A counter-based scheme is fine (offset it so codes are at least 6 chars).
3. **Dedup:** calling `shorten` with the same URL again returns the **same code**, as long as that earlier generated
   link has not expired. Aliases are never reused by dedup.
4. **Aliases** must match `[A-Za-z0-9_-]{3,32}`, else `ValueError`. Already taken (by an alias or a generated code,
   even if expired) → `AliasTakenError` (defined in `service.py`).
5. **Expiry:** a link created at `t` with TTL `d` resolves while `now < t + d`. Expired links return `None` and don't
   count hits.
6. **Stats:** `hits` counts successful resolves; `last_access_millis` is the time of the latest successful resolve,
   or `-1` if never accessed.
7. Thread-safe.
