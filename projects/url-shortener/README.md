# URL Shortener Service

**Scenario.** Marketing wants short links for campaigns: some permanent, some that expire after a promo ends,
and they want click counts. Build the core service (no HTTP layer here, just the domain logic).

## What to build

Package `shortener`.

### `Base62` (static utility)
- Alphabet: `0-9`, then `A-Z`, then `a-z` (so `0 → "0"`, `61 → "z"`, `62 → "10"`).
- `String encode(long n)` for `n >= 0` (negative → `IllegalArgumentException`).
- `long decode(String s)` (empty, null, or invalid char → `IllegalArgumentException`).

### `UrlShortener(TimeSource time)`
| Method | Behaviour |
|---|---|
| `String shorten(String longUrl, long ttlMillis)` | Generate a code. `ttlMillis <= 0` means never expires. |
| `String shortenWithAlias(String longUrl, String alias, long ttlMillis)` | Use a custom code. |
| `Optional<String> resolve(String code)` | Long URL if code exists and is not expired. Counts a hit. |
| `LinkStats stats(String code)` | Hits and last access time. Unknown code → `NoSuchElementException`. |

## Rules
1. **URL validation:** must start with `http://` or `https://`, have something after the scheme, no whitespace,
   max 2048 chars → otherwise `IllegalArgumentException`.
2. **Generated codes** are Base62, between 6 and 10 characters, and must never collide with each other or with
   custom aliases. A counter-based scheme is fine. (Pad/offset it so codes are at least 6 chars.)
3. **Dedup:** calling `shorten` with the same URL again returns the **same code**, as long as that earlier
   generated link has not expired. (Aliases are never reused by dedup.)
4. **Aliases:** must match `[A-Za-z0-9_-]{3,32}`, else `IllegalArgumentException`. Already taken (by an alias or a
   generated code, even if expired) → `IllegalStateException`.
5. **Expiry:** a link created at `t` with ttl `d` resolves while `now < t + d` and is expired at `now >= t + d`.
   Expired links return `Optional.empty()` and do **not** count hits.
6. **Stats:** `hits` counts successful resolves; `lastAccessMillis` is the time of the latest successful resolve,
   or `-1` if never accessed.
7. Thread-safe: concurrent `shorten` calls with *different* URLs must get distinct codes; concurrent resolves must
   not lose hits.

## Interview talking points
- Counter + Base62 vs random codes vs hashing the URL: collision handling, predictability, sharding the counter.
- Storage schema: `links(code PK, long_url, created_at, expires_at, is_alias)`, `clicks` as a separate counter table.
- 301 vs 302 redirects and what that does to your click analytics.
