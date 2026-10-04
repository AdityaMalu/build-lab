package ratelimiter;

import java.util.concurrent.ConcurrentHashMap;

public class TokenBucketLimiter implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefill;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }
    }

    private static final double EPSILON = 1e-9;

    private final int capacity;
    private final double refillPerMilli;
    private final TimeSource time;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(int capacity, double refillPerSecond, TimeSource time) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (refillPerSecond < 0 || Double.isNaN(refillPerSecond)) throw new IllegalArgumentException("refill must be >= 0");
        if (time == null) throw new IllegalArgumentException("time required");
        this.capacity = capacity;
        this.refillPerMilli = refillPerSecond / 1000.0;
        this.time = time;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId required");
        Bucket bucket = buckets.computeIfAbsent(clientId, k -> new Bucket(capacity, time.nowMillis()));
        synchronized (bucket) {
            long now = time.nowMillis();
            long elapsed = Math.max(0, now - bucket.lastRefill);
            bucket.tokens = Math.min(capacity, bucket.tokens + elapsed * refillPerMilli);
            bucket.lastRefill = now;
            if (bucket.tokens >= 1.0 - EPSILON) { // tolerate floating point drift
                bucket.tokens = Math.max(0, bucket.tokens - 1.0);
                return true;
            }
            return false;
        }
    }
}
