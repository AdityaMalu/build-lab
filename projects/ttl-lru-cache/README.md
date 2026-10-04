# TTL + LRU Cache

**Scenario.** A product-catalog service calls a slow pricing backend. You're adding an in-process cache in
front of it: bounded in size, entries that go stale after a while, and protection against a *cache stampede*
(a hot key expires and 50 request threads all hit the backend at once).

## What to build (package `cache`)
`TimeSource` and `CacheStats(hits, misses, evictions, expirations)` are given. Implement
`TtlLruCache<K, V>(int capacity, long defaultTtlMillis, TimeSource time)`.

| Method | Behaviour |
|---|---|
| `V get(K key)` | The value, or `null` if absent or expired. A successful `get` makes the entry **most recently used**. It does **not** extend its TTL. |
| `void put(K key, V value)` | Insert or replace with the default TTL. |
| `void put(K key, V value, long ttlMillis)` | Per-entry TTL. `ttlMillis <= 0` means the entry never expires. Replacing a key resets its TTL and makes it most recently used. |
| `boolean remove(K key)` | `true` if a live entry was removed. |
| `int size()` | Number of **live** (non-expired) entries. |
| `V computeIfAbsent(K key, Function<K, V> loader)` | Return the cached value, or load, cache and return it. See *single flight* below. |
| `CacheStats stats()` | Counters since creation. |

## Rules
1. **Expiry:** an entry written at `t` with TTL `d` is live while `now < t + d` and expired at `now >= t + d`.
   Expired entries are removed lazily (when touched, or when room is needed) and each removal adds 1 to
   `expirations`.
2. **Capacity:** inserting a **new** key into a full cache first removes **all expired entries**; only if the cache
   is still full is the **least recently used** live entry evicted (adds 1 to `evictions`). Replacing an existing
   key never evicts anything.
3. **Stats:** `get` and `computeIfAbsent` count a **hit** when a live value is found, otherwise a **miss**.
4. **Single flight:** if many threads call `computeIfAbsent` for the same missing key at the same time, the loader
   runs **once**; the other callers wait and receive the same value. A loader that throws: the exception reaches
   every waiting caller, nothing is cached, and the next call tries again. A `null` result is returned but not cached.
5. **Validation** → `IllegalArgumentException`: `capacity < 1`, null `time`, null key, null value, null loader.
   `defaultTtlMillis <= 0` means "never expires by default".
6. **Thread-safe.** The cache must never hold more than `capacity` entries.

## Hints
- `new LinkedHashMap<>(16, 0.75f, true)` keeps entries in access order; its first key is the LRU one.
- For single flight, keep a `Map<K, CompletableFuture<V>>` of loads in progress. Run the loader **outside** the
  cache lock, or one slow load blocks every other key.

## Talking points
LRU vs LFU vs TTL-only; why stampedes happen; how Caffeine/Guava handle this (`refreshAfterWrite`,
probabilistic early expiry); local cache vs Redis.
