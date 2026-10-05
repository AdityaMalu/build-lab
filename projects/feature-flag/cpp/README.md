# Feature Flags & A/B Assignment (C++)

**Scenario.** Product teams want to ship code dark, ramp features to a percentage of users, and run A/B tests.
The most important property: **a given user must always land in the same bucket**, on every server, after every
restart, with no stored assignment table.

## What to build (header `flags.hpp`, C++20)
`Flag{key, enabled, rolloutPercent, rules, variants}`, `Rule{attribute, equalsValue, variant}`,
`Variant{name, weight}` and `User{id, attributes}` are given.

### `uint32_t bucket(const std::string& key, uint32_t buckets)`
Deterministic hash → `[0, buckets)`, using **32-bit FNV-1a** over the bytes of `key`:
```
uint32_t h = 0x811C9DC5;
for each byte b: h ^= (unsigned char)b; h *= 0x01000193;
return h % buckets;
```

### `class FlagService`
- `void define(const Flag& f)`: adds or replaces a flag by key. Validate: key non-blank,
  `0 ≤ rolloutPercent ≤ 100`, variant weights each ≥ 0 and (if there are variants) summing to exactly 100 →
  else `std::invalid_argument`.
- `std::string evaluate(const std::string& flagKey, const User& u)`:
  1. Unknown flag → `std::out_of_range`. Blank user id → `std::invalid_argument`.
  2. Flag disabled → `"off"`.
  3. **Rules** in order: the first rule whose `attribute` equals `equalsValue` in `u.attributes` wins → return its
     `variant` (rules bypass the rollout).
  4. **Rollout:** `bucket(flagKey + ":" + u.id, 100) < rolloutPercent` → in; otherwise `"off"`.
  5. In with no variants → `"on"`. Otherwise `b = bucket(flagKey + ":variant:" + u.id, 100)`, walk the variants
     accumulating weights, and return the first one with `b < cumulative`.
- Thread-safe for concurrent `define` and `evaluate`.
