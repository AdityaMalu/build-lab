# Retrying Job Queue

**Scenario.** Your team sends emails, generates invoices and resizes uploads in the background. Workers on many
machines pull jobs from a shared queue. Workers crash, hang and occasionally come back from the dead after their job
was given to someone else. Build the queue so that every job is handed out to **one worker at a time**, failures are
retried with backoff, and hopeless jobs end up in a **dead-letter** list instead of retrying forever.

## Given (package `jobs`)
`TimeSource`, `JobStatus` (`PENDING`, `RUNNING`, `SUCCEEDED`, `DEAD`, `CANCELLED`),
`RetryPolicy(maxAttempts, baseBackoffMillis, maxBackoffMillis)` (validates itself),
`Lease(jobId, token, type, payload, attempt, leaseUntil)` and
`JobView(id, type, priority, status, attempts, nextRunAt, lastError)`.

## Build `JobQueue(TimeSource time, RetryPolicy policy)`

| Method | Behaviour |
|---|---|
| `String submit(type, payload, priority, runAtMillis)` | New `PENDING` job, ids `"J1"`, `"J2"`, ... Blank type → `IllegalArgumentException`; null payload is stored as `""`. |
| `Optional<Lease> poll(workerId, leaseMillis)` | Hand the best **ready** job (`PENDING` and `runAt <= now`) to this worker: it becomes `RUNNING`, `attempts` goes up by 1, and it's leased until `now + leaseMillis`. Empty if nothing is ready. Blank worker or `leaseMillis <= 0` → `IllegalArgumentException`. |
| `void complete(jobId, token)` | → `SUCCEEDED`. |
| `void fail(jobId, token, error)` | Records `lastError` (null → `"failed"`) and retries or dead-letters (below). |
| `JobView view(jobId)` | Current state. |
| `List<JobView> deadLetters()` | All `DEAD` jobs in submission order. |
| `void retryDead(jobId)` | Only from `DEAD`: back to `PENDING`, `attempts = 0`, ready now. |
| `void cancel(jobId)` | Only from `PENDING` → `CANCELLED`. |

Unknown job id → `NoSuchElementException`. Wrong-state `retryDead`/`cancel` → `IllegalStateException`.

## Rules
1. **Which job is best:** highest `priority` first; then earliest `runAt`; then submission order.
2. **Leases and tokens:** every successful `poll` creates a **new unique token**. `complete` and `fail` only work with
   the token of the job's *current* lease while it is `RUNNING`, otherwise `IllegalStateException`. That stops a
   zombie worker from completing a job that has since been given to someone else.
3. **Lease expiry:** a lease is valid while `now < leaseUntil`. Once it expires, the job counts as a failed attempt with
   error `"lease expired"`, as if `fail` had been called at the moment `leaseUntil` was reached. Expired leases are
   processed before any `poll`, `complete` or `fail` does its own work.
4. **Retry or dead-letter:** after a failed attempt, if `attempts < maxAttempts` the job goes back to `PENDING` with
   `runAt = failureTime + backoff`, where after the *n*-th attempt
   `backoff = min(maxBackoff, baseBackoff × 2^(n-1))` (1s, 2s, 4s, ... with base 1000). Otherwise it becomes `DEAD`.
5. **Exactly one worker:** with many threads polling at once, a job is never leased to two workers at the same time.

## Talking points
At-least-once vs exactly-once delivery and why jobs must be idempotent; visibility timeouts in SQS; why add jitter
to backoff; heartbeats to extend a lease; poison messages and dead-letter queues.
