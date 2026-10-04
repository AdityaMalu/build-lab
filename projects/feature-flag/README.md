# Feature Flags & A/B Assignment

**Scenario.** Product teams want to ship code dark, ramp features to a percentage of users, and run A/B tests.
The most important property: **a given user must always land in the same bucket**, on every server, after every
restart, with no stored assignment table.

## What to build

Package `flags`. Records `Flag`, `Rule`, `Variant`, `User` are given. Implement `Bucketing` and `FlagService`.

### `Bucketing.bucket(String key, int buckets)`
Deterministic hash → `[0, buckets)`. Use **32-bit FNV-1a** over the UTF-8 bytes of `key`:
```
hash = 0x811C9DC5
for each byte b: hash ^= (b & 0xFF); hash *= 0x01000193   (32-bit overflow)
result = Integer.toUnsignedLong(hash) % buckets
```
(The tests pin exact values so that "every server agrees".)

### `FlagService`
- `void define(Flag flag)` adds or replaces a flag by key. Validate: key non-blank, `0 ≤ rolloutPercent ≤ 100`,
  variant weights each ≥ 0 and (if any variants) summing to exactly 100, rules non-null → else `IllegalArgumentException`.
- `String evaluate(String flagKey, User user)`:
  1. Unknown flag → `IllegalArgumentException`. Null user / blank user id → `IllegalArgumentException`.
  2. Flag disabled → `"off"`.
  3. **Targeting rules** in list order: first rule whose `attribute` equals `equalsValue` in the user's attributes
     wins → return that rule's `variant`. (Rules bypass the rollout percentage.)
  4. **Rollout:** `Bucketing.bucket(flagKey + ":" + userId, 100) < rolloutPercent` → user is in; otherwise `"off"`.
  5. In: if there are no variants, return `"on"`. Otherwise compute
     `b = Bucketing.bucket(flagKey + ":variant:" + userId, 100)` and walk the variants in order, accumulating
     weights; return the first variant where `b < cumulativeWeight`.
- Safe for concurrent `define` and `evaluate` calls; `evaluate` must never see a half-updated flag.

## Why the rollout and variant hashes use different salts
If you reused the same bucket for both, everyone in the first 10% of the rollout would also be in the first 10% of
the variant split, which skews the experiment. Be ready to explain this.
