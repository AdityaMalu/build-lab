# Feature Flags & A/B Assignment (Python)

**Scenario.** Product teams want to ship code dark, ramp features to a percentage of users, and run A/B tests.
The most important property: **a given user must always land in the same bucket**, on every server, after every
restart, with no stored assignment table.

## What to build (package `flags`)
Frozen dataclasses `Flag(key, enabled, rollout_percent, rules, variants)`, `Rule(attribute, equals_value, variant)`,
`Variant(name, weight)` and `User(id, attributes)` are given.

### `bucket(key: str, buckets: int) -> int`
Deterministic hash → `[0, buckets)`, using **32-bit FNV-1a** over the UTF-8 bytes of `key`:
```
h = 0x811C9DC5
for each byte b: h ^= b; h = (h * 0x01000193) mod 2**32
result = h % buckets
```

### `FlagService`
- `define(flag)` adds or replaces a flag by key. Validate: key non-blank, `0 ≤ rollout_percent ≤ 100`, variant
  weights each ≥ 0 and (if there are variants) summing to exactly 100, `rules` not `None` → else `ValueError`.
  Store your own copy so callers can't change the flag afterwards.
- `evaluate(flag_key, user) -> str`:
  1. Unknown flag → `KeyError`. `None` user or blank user id → `ValueError`.
  2. Flag disabled → `"off"`.
  3. **Rules** in order: the first rule whose `attribute` equals `equals_value` in `user.attributes` wins → return
     its `variant` (rules bypass the rollout). `attributes` may be `None`.
  4. **Rollout:** `bucket(flag_key + ":" + user.id, 100) < rollout_percent` → in; otherwise `"off"`.
  5. In with no variants → `"on"`. Otherwise `b = bucket(flag_key + ":variant:" + user.id, 100)` and walk the
     variants accumulating weights; return the first variant with `b < cumulative`.
- Safe for concurrent `define` and `evaluate`; `evaluate` never sees a half-updated flag.
