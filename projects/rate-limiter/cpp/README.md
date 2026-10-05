# Rate Limiter (C++)

**Scenario.** Your team runs a gateway in front of an expensive model-inference API. A few noisy clients
are burning the shared quota. Build an in-memory, thread-safe rate limiter that the gateway calls on every
request.

## What to build (header `rate_limiter.hpp`, C++20)

```cpp
struct Clock { virtual ~Clock() = default; virtual int64_t nowMillis() const = 0; };   // tests pass a fake
struct RateLimiter {
    virtual ~RateLimiter() = default;
    virtual bool tryAcquire(const std::string& clientId) = 0;   // true = let the request through
};
```

### 1. `TokenBucketLimiter(int capacity, double refillPerSecond, const Clock& clock)`
- Each client starts with a **full** bucket of `capacity` tokens.
- Every allowed request consumes one token.
- Tokens refill continuously at `refillPerSecond` (fractional tokens accumulate), never exceeding `capacity`.

### 2. `SlidingWindowLimiter(int maxRequests, int64_t windowMillis, const Clock& clock)`
- A client may have at most `maxRequests` **allowed** requests in any window `(now - windowMillis, now]`.
- Rejected requests do not count toward the limit.

## Rules
- Clients are independent of each other.
- Invalid constructor arguments (capacity ≤ 0, negative refill, maxRequests ≤ 0, windowMillis ≤ 0) →
  `std::invalid_argument`. An empty or all-whitespace `clientId` → `std::invalid_argument`.
- Never read the real clock; always use the injected `Clock` (the limiter keeps a reference to it).
- **Must be thread-safe.** 100 threads hammering the same client must never let through more requests than allowed.

## Hints
- `std::unordered_map<std::string, std::unique_ptr<Bucket>>` guarded by a `std::mutex`, plus a mutex per bucket.
  `unique_ptr` keeps each bucket's address stable when the map rehashes.
- For the sliding window, a `std::deque<int64_t>` of timestamps per client works well.
- Floating-point refill: compare with a small tolerance, e.g. `tokens >= 1 - 1e-9`.
