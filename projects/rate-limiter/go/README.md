# Rate Limiter (Go)

**Scenario.** Your team runs a gateway in front of an expensive model-inference API. A few noisy clients
are burning the shared quota. Build an in-memory, goroutine-safe rate limiter that the gateway calls on every
request.

## What to build (package `ratelimiter`, file `limiter.go`)

```go
type Clock interface{ NowMillis() int64 }       // tests pass a fake clock
type Limiter interface {
    TryAcquire(clientID string) (bool, error)   // true = let the request through
}
var ErrInvalidArgument = errors.New("invalid argument")
```

### 1. `NewTokenBucket(capacity int, refillPerSecond float64, clock Clock) (*TokenBucket, error)`
- Each client starts with a **full** bucket of `capacity` tokens.
- Every allowed request consumes one token.
- Tokens refill continuously at `refillPerSecond` (fractional tokens accumulate), never exceeding `capacity`.

### 2. `NewSlidingWindow(maxRequests int, windowMillis int64, clock Clock) (*SlidingWindow, error)`
- A client may have at most `maxRequests` **allowed** requests in any window `(now - windowMillis, now]`.
- Rejected requests do not count toward the limit.

## Rules
- Clients are independent of each other.
- Invalid constructor arguments (capacity ≤ 0, negative refill, maxRequests ≤ 0, windowMillis ≤ 0, nil clock) return
  an error that wraps `ErrInvalidArgument` (check with `errors.Is`). A blank `clientID` makes `TryAcquire` return
  `false` and such an error.
- Never call `time.Now()`; always use the injected `Clock`.
- **Must be safe for concurrent use.** 100 goroutines hammering the same client must never let through more requests
  than allowed. Run your tests with `go test -race` locally if you can.

## Hints
- A `map[string]*bucket` guarded by a `sync.Mutex`, and a mutex per bucket, keeps clients from blocking each other.
- Wrap errors with `fmt.Errorf("capacity must be positive: %w", ErrInvalidArgument)`.
- Floating-point refill: compare with a small tolerance, e.g. `tokens >= 1-1e-9`.
