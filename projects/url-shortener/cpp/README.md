# URL Shortener Service (C++)

**Scenario.** Marketing wants short links for campaigns: some permanent, some that expire after a promo ends,
and they want click counts. Build the core service (no HTTP layer here, just the domain logic).

## What to build (header `shortener.hpp`, C++20)

### `namespace base62`
- Alphabet: `0-9`, then `A-Z`, then `a-z` (so `0 → "0"`, `61 → "z"`, `62 → "10"`).
- `std::string encode(long long n)`: negative → `std::invalid_argument`.
- `long long decode(const std::string& s)`: empty or an invalid character → `std::invalid_argument`.

### `class UrlShortener` (constructed with `const Clock&`)

| Method | Behaviour |
|---|---|
| `std::string shorten(const std::string& url, int64_t ttlMillis)` | Generate a code. `ttlMillis <= 0` means never expires. |
| `std::string shortenWithAlias(const std::string& url, const std::string& alias, int64_t ttlMillis)` | Use a custom code. |
| `std::optional<std::string> resolve(const std::string& code)` | The long URL if the code exists and isn't expired. Counts a hit. |
| `LinkStats stats(const std::string& code)` | `{code, hits, lastAccessMillis}`. Unknown code → `std::out_of_range`. |

## Rules
1. **URL validation:** must start with `http://` or `https://`, have something after the scheme, no whitespace,
   max 2048 chars → otherwise `std::invalid_argument`.
2. **Generated codes** are Base62, 6–10 characters, and never collide with each other or with aliases.
   A counter-based scheme is fine (offset it so codes are at least 6 chars).
3. **Dedup:** calling `shorten` with the same URL again returns the **same code**, as long as that earlier generated
   link has not expired. Aliases are never reused by dedup.
4. **Aliases** must match `[A-Za-z0-9_-]{3,32}`, else `std::invalid_argument`. Already taken (by an alias or a
   generated code, even if expired) → `AliasTaken` (declared in the header, derives from `std::runtime_error`).
5. **Expiry:** a link created at `t` with TTL `d` resolves while `now < t + d`. Expired links don't resolve and don't
   count hits.
6. **Stats:** `hits` counts successful resolves; `lastAccessMillis` is the time of the latest successful resolve,
   or `-1` if never accessed.
7. Thread-safe.
