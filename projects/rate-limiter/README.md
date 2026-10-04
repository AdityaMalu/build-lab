# Rate Limiter

**Scenario.** Your team runs a gateway in front of an expensive model-inference API. A few noisy clients
are burning the shared quota. Build an in-memory, thread-safe rate limiter that the gateway calls on every
request.

## What to build

Package `ratelimiter`. The interface is already defined:

```java
public interface RateLimiter {
    boolean tryAcquire(String clientId);   // true = let the request through
}
```

Implement **two policies**:

### 1. `TokenBucketLimiter(int capacity, double refillPerSecond, TimeSource time)`
- Each client starts with a **full** bucket of `capacity` tokens.
- Every allowed request consumes one token.
- Tokens refill continuously at `refillPerSecond` (fractional tokens accumulate), never exceeding `capacity`.

### 2. `SlidingWindowLimiter(int maxRequests, long windowMillis, TimeSource time)`
- A client may have at most `maxRequests` **allowed** requests in any window `(now - windowMillis, now]`.
- Rejected requests do not count toward the limit.

## Rules
- Clients are independent of each other.
- Invalid constructor arguments (capacity ≤ 0, negative refill, maxRequests ≤ 0, windowMillis ≤ 0, null time)
  → `IllegalArgumentException`. A null or blank `clientId` → `IllegalArgumentException`.
- Never read the system clock directly; always use the injected `TimeSource` so tests are deterministic.
- **Must be thread-safe.** 100 threads hammering the same client must never let through more requests than allowed.
- Don't serialize *all* clients behind one global lock if you can avoid it (it's not tested, but an interviewer will ask).

## Hints
- `ConcurrentHashMap.computeIfAbsent` gives you one state object per client; lock on that object.
- For the sliding window, a `Deque<Long>` of timestamps per client works well.

## Follow-ups to think about
- How would you share limits across 20 gateway instances? (Redis + Lua, approximate counters)
- How do you evict idle clients so memory doesn't grow forever?
