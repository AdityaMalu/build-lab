package ratelimiter;

public class TokenBucketLimiter implements RateLimiter {

    public TokenBucketLimiter(int capacity, double refillPerSecond, TimeSource time) {
        // TODO validate arguments and set up per-client state
    }

    @Override
    public boolean tryAcquire(String clientId) {
        // TODO refill the client's bucket based on elapsed time, then try to take one token
        throw new UnsupportedOperationException("TODO");
    }
}
