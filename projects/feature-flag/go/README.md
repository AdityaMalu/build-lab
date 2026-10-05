# Feature Flags & A/B Assignment (Go)

**Scenario.** Product teams want to ship code dark, ramp features to a percentage of users, and run A/B tests.
The most important property: **a given user must always land in the same bucket**, on every server, after every
restart, with no stored assignment table.

## What to build (package `flags`)
`Flag{Key, Enabled, RolloutPercent, Rules, Variants}`, `Rule{Attribute, EqualsValue, Variant}`,
`Variant{Name, Weight}` and `User{ID, Attributes}` are given.

### `Bucket(key string, buckets uint32) uint32`
Deterministic hash → `[0, buckets)`, using **32-bit FNV-1a** over the bytes of `key`:
```
h := uint32(0x811C9DC5)
for each byte b: h ^= uint32(b); h *= 0x01000193
return h % buckets
```
(Don't just import `hash/fnv`: write the loop. It's 4 lines and interviewers like seeing it.)

### `Service` (create with `NewService()`)
- `Define(f Flag) error`: adds or replaces a flag by key. Validate: key non-blank, `0 ≤ RolloutPercent ≤ 100`,
  variant weights each ≥ 0 and (if there are variants) summing to exactly 100 → else an error wrapping
  `ErrInvalidFlag`. Store copies of the slices so callers can't change the flag afterwards.
- `Evaluate(flagKey string, u User) (string, error)`:
  1. Unknown flag → `ErrUnknownFlag`. Blank user id → `ErrInvalidUser`.
  2. Flag disabled → `"off"`.
  3. **Rules** in order: the first rule whose `Attribute` equals `EqualsValue` in `u.Attributes` wins → return its
     `Variant` (rules bypass the rollout). `Attributes` may be nil.
  4. **Rollout:** `Bucket(flagKey+":"+u.ID, 100) < RolloutPercent` → in; otherwise `"off"`.
  5. In with no variants → `"on"`. Otherwise `b := Bucket(flagKey+":variant:"+u.ID, 100)`, walk the variants
     accumulating weights, and return the first one with `b < cumulative`.
- Safe for concurrent `Define` and `Evaluate`.
