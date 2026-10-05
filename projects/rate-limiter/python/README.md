# Rate Limiter (Python)

**Scenario.** Your team runs a gateway in front of an expensive model-inference API. A few noisy clients
are burning the shared quota. Build an in-memory, thread-safe rate limiter that the gateway calls on every
request.

## What to build (package `ratelimiter`, file `limiters.py`)

Every limiter has one method:

```python
def try_acquire(self, client_id: str) -> bool:   # True = let the request through
```

The `time` argument is any object with a `now_millis() -> int` method (tests pass a fake clock).

### 1. `TokenBucketLimiter(capacity: int, refill_per_second: float, time)`
- Each client starts with a **full** bucket of `capacity` tokens.
- Every allowed request consumes one token.
- Tokens refill continuously at `refill_per_second` (fractional tokens accumulate), never exceeding `capacity`.

### 2. `SlidingWindowLimiter(max_requests: int, window_millis: int, time)`
- A client may have at most `max_requests` **allowed** requests in any window `(now - window_millis, now]`.
- Rejected requests do not count toward the limit.

## Rules
- Clients are independent of each other.
- Invalid constructor arguments (capacity ≤ 0, negative refill, max_requests ≤ 0, window_millis ≤ 0, `time` is `None`)
  → `ValueError`. A `None` or blank `client_id` → `ValueError`.
- Never read the real clock; always use the injected `time`.
- **Must be thread-safe.** 100 threads hammering the same client must never let through more requests than allowed.
  (The GIL does not make `x = x + 1` atomic.)

## Hints
- One state object per client in a dict, plus a `threading.Lock`. A lock per client is better than one global lock.
- For the sliding window, a `collections.deque` of timestamps per client works well.
- Floating-point refill: compare with a small tolerance, e.g. `tokens >= 1 - 1e-9`.
